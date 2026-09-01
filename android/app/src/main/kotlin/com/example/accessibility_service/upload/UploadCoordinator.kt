package com.example.accessibility_service.upload

import com.example.accessibility_service.NativeTokenStore
import com.example.accessibility_service.networking.CapturesApiClient
import com.example.accessibility_service.networking.CapturesApiResult
import com.example.accessibility_service.networking.InvalidRequestReason
import com.example.accessibility_service.persistence.AcknowledgeResult
import com.example.accessibility_service.persistence.PersistentEventQueue
import com.example.accessibility_service.persistence.QueueBatchToken
import com.example.accessibility_service.persistence.QueuedCapture
import com.example.accessibility_service.persistence.QueuedCaptureBatch
import kotlinx.coroutines.sync.Mutex

data class UploadRuntimeConfig(
    val batchSize: Int,
    val flushSeconds: Int,
)

enum class RetryableFailureReason {
    SERVICE_UNAVAILABLE,
    TIMEOUT,
    NETWORK_ERROR,
}

enum class AcknowledgmentSource {
    SUCCESS,
    UNPROCESSABLE,
}

sealed interface UploadOutcome {
    data class Uploaded(
        val accepted: Int,
        val skipped: Int,
        val acknowledgment: AcknowledgeResult,
    ) : UploadOutcome

    data object QueueEmpty : UploadOutcome
    data object NoValidToken : UploadOutcome
    data object AlreadyRunning : UploadOutcome
    data object TokenRevoked : UploadOutcome

    data class BatchDiscardedUnprocessable(
        val acknowledgment: AcknowledgeResult,
    ) : UploadOutcome

    data class RetryableFailure(val reason: RetryableFailureReason) : UploadOutcome
    data class InvalidResponse(val reason: String) : UploadOutcome
    data class OtherHttpFailure(val statusCode: Int) : UploadOutcome
    data class InvalidRequest(val reason: InvalidRequestReason) : UploadOutcome
    data class AckConflict(val source: AcknowledgmentSource) : UploadOutcome
}

class UploadCoordinator internal constructor(
    private val tokenStore: UploadTokenStore,
    private val queue: UploadEventQueue,
    private val apiClient: UploadCapturesClient,
) {
    constructor(
        tokenStore: NativeTokenStore,
        queue: PersistentEventQueue,
        apiClient: CapturesApiClient = CapturesApiClient(),
    ) : this(
        tokenStore = NativeTokenStoreAdapter(tokenStore),
        queue = PersistentEventQueueAdapter(queue),
        apiClient = CapturesApiClientAdapter(apiClient),
    )

    private val uploadMutex = Mutex()

    @Volatile
    private var config = UploadRuntimeConfig(
        batchSize = DEFAULT_BATCH_SIZE,
        flushSeconds = DEFAULT_FLUSH_SECONDS,
    )

    val runtimeConfig: UploadRuntimeConfig
        get() = config

    suspend fun requestUpload(): UploadOutcome {
        if (!uploadMutex.tryLock()) return UploadOutcome.AlreadyRunning

        return try {
            uploadOneBatch()
        } finally {
            uploadMutex.unlock()
        }
    }

    private suspend fun uploadOneBatch(): UploadOutcome {
        val token = tokenStore.getToken()
        if (token.isNullOrBlank()) return UploadOutcome.NoValidToken

        val batch = queue.peekBatch(config.batchSize)
        if (batch.captures.isEmpty()) return UploadOutcome.QueueEmpty
        val acknowledgmentToken = checkNotNull(batch.acknowledgmentToken) {
            "A non-empty queue batch must include an acknowledgment token."
        }

        return when (val result = apiClient.sendCaptures(token, batch.captures)) {
            is CapturesApiResult.Success -> {
                applyValidConfig(result.response.config.batchSize, result.response.config.flushSeconds)
                acknowledgeAcceptedBatch(
                    token = acknowledgmentToken,
                    accepted = result.response.accepted,
                    skipped = result.response.skipped,
                )
            }
            CapturesApiResult.Unauthorized -> {
                tokenStore.revokeToken()
                UploadOutcome.TokenRevoked
            }
            CapturesApiResult.Unprocessable -> acknowledgeUnprocessableBatch(acknowledgmentToken)
            CapturesApiResult.ServiceUnavailable ->
                UploadOutcome.RetryableFailure(RetryableFailureReason.SERVICE_UNAVAILABLE)
            CapturesApiResult.Timeout ->
                UploadOutcome.RetryableFailure(RetryableFailureReason.TIMEOUT)
            is CapturesApiResult.NetworkError ->
                UploadOutcome.RetryableFailure(RetryableFailureReason.NETWORK_ERROR)
            is CapturesApiResult.InvalidResponse -> UploadOutcome.InvalidResponse(result.reason)
            is CapturesApiResult.OtherHttpError -> UploadOutcome.OtherHttpFailure(result.statusCode)
            is CapturesApiResult.InvalidRequest -> UploadOutcome.InvalidRequest(result.reason)
        }
    }

    private suspend fun acknowledgeAcceptedBatch(
        token: QueueBatchToken,
        accepted: Int,
        skipped: Int,
    ): UploadOutcome = when (val acknowledgment = queue.acknowledge(token)) {
        AcknowledgeResult.Stale -> UploadOutcome.AckConflict(AcknowledgmentSource.SUCCESS)
        else -> UploadOutcome.Uploaded(accepted, skipped, acknowledgment)
    }

    private suspend fun acknowledgeUnprocessableBatch(token: QueueBatchToken): UploadOutcome =
        when (val acknowledgment = queue.acknowledge(token)) {
            AcknowledgeResult.Stale -> UploadOutcome.AckConflict(AcknowledgmentSource.UNPROCESSABLE)
            else -> UploadOutcome.BatchDiscardedUnprocessable(acknowledgment)
        }

    private fun applyValidConfig(batchSize: Int, flushSeconds: Int) {
        val current = config
        config = UploadRuntimeConfig(
            batchSize = batchSize.takeIf { it in 1..MAX_BATCH_SIZE } ?: current.batchSize,
            flushSeconds = flushSeconds.takeIf { it > 0 } ?: current.flushSeconds,
        )
    }

    companion object {
        const val DEFAULT_BATCH_SIZE = 30
        const val DEFAULT_FLUSH_SECONDS = 20
        const val MAX_BATCH_SIZE = CapturesApiClient.MAX_EVENTS_PER_REQUEST
    }
}

internal interface UploadTokenStore {
    suspend fun getToken(): String?
    suspend fun revokeToken()
}

internal interface UploadEventQueue {
    suspend fun peekBatch(maxCount: Int): QueuedCaptureBatch
    suspend fun acknowledge(token: QueueBatchToken): AcknowledgeResult
}

internal interface UploadCapturesClient {
    suspend fun sendCaptures(token: String, captures: List<QueuedCapture>): CapturesApiResult
}

private class NativeTokenStoreAdapter(
    private val delegate: NativeTokenStore,
) : UploadTokenStore {
    override suspend fun getToken(): String? = delegate.getToken()

    override suspend fun revokeToken() = delegate.clearToken()
}

private class PersistentEventQueueAdapter(
    private val delegate: PersistentEventQueue,
) : UploadEventQueue {
    override suspend fun peekBatch(maxCount: Int): QueuedCaptureBatch = delegate.peekBatch(maxCount)

    override suspend fun acknowledge(token: QueueBatchToken): AcknowledgeResult = delegate.acknowledge(token)
}

private class CapturesApiClientAdapter(
    private val delegate: CapturesApiClient,
) : UploadCapturesClient {
    override suspend fun sendCaptures(
        token: String,
        captures: List<QueuedCapture>,
    ): CapturesApiResult = delegate.sendCaptures(token, captures)
}
