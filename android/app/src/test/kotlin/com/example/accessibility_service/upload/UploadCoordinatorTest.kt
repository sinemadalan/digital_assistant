package com.example.accessibility_service.upload

import com.example.accessibility_service.networking.CapturesApiResult
import com.example.accessibility_service.networking.CapturesConfig
import com.example.accessibility_service.networking.CapturesResponse
import com.example.accessibility_service.networking.InvalidRequestReason
import com.example.accessibility_service.persistence.AcknowledgeResult
import com.example.accessibility_service.persistence.QueueBatchToken
import com.example.accessibility_service.persistence.QueuedCapture
import com.example.accessibility_service.persistence.QueuedCaptureBatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadCoordinatorTest {
    @Test
    fun noTokenReturnsWithoutTouchingQueueOrNetwork() = runTest {
        val tokenStore = FakeTokenStore(null)
        val queue = FakeQueue(batchOf(capture()))
        val client = FakeClient(success())

        val outcome = coordinator(tokenStore, queue, client).requestUpload()

        assertEquals(UploadOutcome.NoValidToken, outcome)
        assertEquals(0, queue.peekCalls)
        assertEquals(0, queue.acknowledgeCalls)
        assertEquals(0, client.calls)
    }

    @Test
    fun blankTokenIsNotUsable() = runTest {
        val queue = FakeQueue(batchOf(capture()))
        val client = FakeClient(success())

        assertEquals(
            UploadOutcome.NoValidToken,
            coordinator(FakeTokenStore("  "), queue, client).requestUpload(),
        )
        assertEquals(0, queue.peekCalls)
        assertEquals(0, client.calls)
    }

    @Test
    fun emptyQueueReturnsWithoutHttpOrAcknowledgment() = runTest {
        val queue = FakeQueue(QueuedCaptureBatch(emptyList(), null))
        val client = FakeClient(success())

        val outcome = coordinator(FakeTokenStore("token"), queue, client).requestUpload()

        assertEquals(UploadOutcome.QueueEmpty, outcome)
        assertEquals(listOf(30), queue.peekSizes)
        assertEquals(0, queue.acknowledgeCalls)
        assertEquals(0, client.calls)
    }

    @Test
    fun oneInvocationPeeksPostsAndAcknowledgesAtMostOneBatch() = runTest {
        val queue = FakeQueue(batchOf(capture(1), capture(2)))
        val client = FakeClient(success(accepted = 2))

        val outcome = coordinator(FakeTokenStore("token"), queue, client).requestUpload()

        assertTrue(outcome is UploadOutcome.Uploaded)
        assertEquals(1, queue.peekCalls)
        assertEquals(1, client.calls)
        assertEquals(1, queue.acknowledgeCalls)
        assertEquals(2, client.lastCaptures.size)
    }

    @Test
    fun concurrentRequestReturnsAlreadyRunningAndStartsNoSecondPost() = runTest {
        val enteredClient = CompletableDeferred<Unit>()
        val releaseClient = CompletableDeferred<Unit>()
        val client = FakeClient { _, captures ->
            enteredClient.complete(Unit)
            releaseClient.await()
            success(accepted = captures.size)
        }
        val coordinator = coordinator(
            FakeTokenStore("token"),
            FakeQueue(batchOf(capture())),
            client,
        )

        val first = async { coordinator.requestUpload() }
        enteredClient.await()
        val second = coordinator.requestUpload()
        releaseClient.complete(Unit)

        assertEquals(UploadOutcome.AlreadyRunning, second)
        assertTrue(first.await() is UploadOutcome.Uploaded)
        assertEquals(1, client.calls)
    }

    @Test
    fun successTreatsAllSafeAcknowledgmentResultsAsUploaded() = runTest {
        val safeResults = listOf(
            AcknowledgeResult.FullyAcknowledged,
            AcknowledgeResult.RemainingSuffixAcknowledged(evictedPrefixCount = 2),
            AcknowledgeResult.AlreadyEvicted,
        )

        for (acknowledgment in safeResults) {
            val queue = FakeQueue(batchOf(capture()), acknowledgment)
            val outcome = coordinator(
                FakeTokenStore("token"),
                queue,
                FakeClient(success()),
            ).requestUpload()

            assertEquals(UploadOutcome.Uploaded(1, 0, acknowledgment), outcome)
        }
    }

    @Test
    fun successWithStaleAcknowledgmentReportsConflict() = runTest {
        val queue = FakeQueue(batchOf(capture()), AcknowledgeResult.Stale)

        val outcome = coordinator(
            FakeTokenStore("token"),
            queue,
            FakeClient(success()),
        ).requestUpload()

        assertEquals(UploadOutcome.AckConflict(AcknowledgmentSource.SUCCESS), outcome)
        assertEquals(1, queue.acknowledgeCalls)
    }

    @Test
    fun successWithSkippedStillAcknowledgesEntirePeekedBatch() = runTest {
        val queue = FakeQueue(batchOf(capture(1), capture(2), capture(3)))

        val outcome = coordinator(
            FakeTokenStore("token"),
            queue,
            FakeClient(success(accepted = 2, skipped = 1)),
        ).requestUpload()

        assertEquals(
            UploadOutcome.Uploaded(2, 1, AcknowledgeResult.FullyAcknowledged),
            outcome,
        )
        assertEquals(1, queue.acknowledgeCalls)
    }

    @Test
    fun unauthorizedDoesNotAckAndRevokesTokenSoNextRequestDoesNotPost() = runTest {
        val tokenStore = FakeTokenStore("rejected-token")
        val queue = FakeQueue(batchOf(capture()))
        val client = FakeClient(CapturesApiResult.Unauthorized)
        val coordinator = coordinator(tokenStore, queue, client)

        val first = coordinator.requestUpload()
        val second = coordinator.requestUpload()

        assertEquals(UploadOutcome.TokenRevoked, first)
        assertEquals(UploadOutcome.NoValidToken, second)
        assertEquals(1, tokenStore.revokeCalls)
        assertTrue(tokenStore.reauthenticationRequired)
        assertEquals(1, queue.peekCalls)
        assertEquals(0, queue.acknowledgeCalls)
        assertEquals(1, client.calls)
    }

    @Test
    fun unprocessableAcknowledgesAndDiscardsBatch() = runTest {
        val queue = FakeQueue(
            batchOf(capture()),
            AcknowledgeResult.RemainingSuffixAcknowledged(evictedPrefixCount = 1),
        )

        val outcome = coordinator(
            FakeTokenStore("token"),
            queue,
            FakeClient(CapturesApiResult.Unprocessable),
        ).requestUpload()

        assertEquals(
            UploadOutcome.BatchDiscardedUnprocessable(
                AcknowledgeResult.RemainingSuffixAcknowledged(evictedPrefixCount = 1),
            ),
            outcome,
        )
        assertEquals(1, queue.acknowledgeCalls)
    }

    @Test
    fun unprocessableWithStaleAcknowledgmentReportsConflict() = runTest {
        val queue = FakeQueue(batchOf(capture()), AcknowledgeResult.Stale)

        val outcome = coordinator(
            FakeTokenStore("token"),
            queue,
            FakeClient(CapturesApiResult.Unprocessable),
        ).requestUpload()

        assertEquals(UploadOutcome.AckConflict(AcknowledgmentSource.UNPROCESSABLE), outcome)
        assertEquals(1, queue.acknowledgeCalls)
    }

    @Test
    fun retryableFailuresPreserveQueueAndTokenWithoutAutomaticRetry() = runTest {
        val cases = listOf(
            CapturesApiResult.ServiceUnavailable to RetryableFailureReason.SERVICE_UNAVAILABLE,
            CapturesApiResult.Timeout to RetryableFailureReason.TIMEOUT,
            CapturesApiResult.NetworkError(IllegalStateException("offline")) to RetryableFailureReason.NETWORK_ERROR,
        )

        for ((apiResult, expectedReason) in cases) {
            val tokenStore = FakeTokenStore("token")
            val queue = FakeQueue(batchOf(capture()))
            val client = FakeClient(apiResult)

            val outcome = coordinator(tokenStore, queue, client).requestUpload()

            assertEquals(UploadOutcome.RetryableFailure(expectedReason), outcome)
            assertEquals(0, queue.acknowledgeCalls)
            assertEquals(0, tokenStore.revokeCalls)
            assertEquals(1, client.calls)
        }
    }

    @Test
    fun failSafeApiResultsDoNotAckOrRevoke() = runTest {
        val cases = listOf(
            CapturesApiResult.InvalidResponse("bad response") to UploadOutcome.InvalidResponse("bad response"),
            CapturesApiResult.OtherHttpError(500) to UploadOutcome.OtherHttpFailure(500),
            CapturesApiResult.InvalidRequest(InvalidRequestReason.TOO_MANY_EVENTS) to
                UploadOutcome.InvalidRequest(InvalidRequestReason.TOO_MANY_EVENTS),
        )

        for ((apiResult, expectedOutcome) in cases) {
            val tokenStore = FakeTokenStore("token")
            val queue = FakeQueue(batchOf(capture()))

            val outcome = coordinator(tokenStore, queue, FakeClient(apiResult)).requestUpload()

            assertEquals(expectedOutcome, outcome)
            assertEquals(0, queue.acknowledgeCalls)
            assertEquals(0, tokenStore.revokeCalls)
        }
    }

    @Test
    fun validConfigUpdatesRuntimeStateAndNextPeekSize() = runTest {
        val queue = FakeQueue(batchOf(capture()))
        val client = FakeClient(success(batchSize = 40, flushSeconds = 25))
        val coordinator = coordinator(FakeTokenStore("token"), queue, client)

        assertEquals(UploadRuntimeConfig(30, 20), coordinator.runtimeConfig)
        coordinator.requestUpload()
        coordinator.requestUpload()

        assertEquals(UploadRuntimeConfig(40, 25), coordinator.runtimeConfig)
        assertEquals(listOf(30, 40), queue.peekSizes)
    }

    @Test
    fun batchSizeFiftyIsAcceptedAndOutOfRangeValuesPreserveCurrentValue() = runTest {
        val results = ArrayDeque(
            listOf(
                success(batchSize = 50),
                success(batchSize = 51),
                success(batchSize = 0),
            ),
        )
        val coordinator = coordinator(
            FakeTokenStore("token"),
            FakeQueue(batchOf(capture())),
            FakeClient { _, _ -> results.removeFirst() },
        )

        coordinator.requestUpload()
        assertEquals(50, coordinator.runtimeConfig.batchSize)
        coordinator.requestUpload()
        assertEquals(50, coordinator.runtimeConfig.batchSize)
        coordinator.requestUpload()
        assertEquals(50, coordinator.runtimeConfig.batchSize)
    }

    @Test
    fun nonPositiveFlushSecondsPreservesCurrentValue() = runTest {
        val results = ArrayDeque(
            listOf(
                success(flushSeconds = 45),
                success(flushSeconds = 0),
                success(flushSeconds = -1),
            ),
        )
        val coordinator = coordinator(
            FakeTokenStore("token"),
            FakeQueue(batchOf(capture())),
            FakeClient { _, _ -> results.removeFirst() },
        )

        repeat(3) { coordinator.requestUpload() }

        assertEquals(45, coordinator.runtimeConfig.flushSeconds)
    }

    @Test
    fun invalidServerConfigDoesNotPreventSuccessfulAcknowledgment() = runTest {
        val queue = FakeQueue(batchOf(capture()))
        val coordinator = coordinator(
            FakeTokenStore("token"),
            queue,
            FakeClient(success(batchSize = 500, flushSeconds = 0)),
        )

        val outcome = coordinator.requestUpload()

        assertTrue(outcome is UploadOutcome.Uploaded)
        assertEquals(UploadRuntimeConfig(30, 20), coordinator.runtimeConfig)
        assertEquals(1, queue.acknowledgeCalls)
    }

    private fun coordinator(
        tokenStore: FakeTokenStore,
        queue: FakeQueue,
        client: FakeClient,
    ) = UploadCoordinator(tokenStore, queue, client)

    private fun success(
        accepted: Int = 1,
        skipped: Int = 0,
        batchSize: Int = 30,
        flushSeconds: Int = 20,
    ): CapturesApiResult.Success = CapturesApiResult.Success(
        CapturesResponse(
            accepted = accepted,
            skipped = skipped,
            config = CapturesConfig(batchSize, flushSeconds),
            commands = emptyList(),
        ),
    )

    private fun batchOf(vararg captures: QueuedCapture): QueuedCaptureBatch = QueuedCaptureBatch(
        captures = captures.toList(),
        acknowledgmentToken = QueueBatchToken(
            readPosition = 0,
            sequences = captures.indices.map { it.toLong() + 1 },
            droppedDueToCapacity = 0,
            ownerMarker = Any(),
            nonCapacityHeadChangeEpoch = 0,
        ),
    )

    private fun capture(index: Int = 1) = QueuedCapture(
        packageName = "com.example.$index",
        appName = "Example $index",
        eventType = "TYPE_WINDOW_CONTENT_CHANGED",
        capturedAtDevice = "2026-09-01T12:00:00+03:00",
        screenText = listOf("content $index"),
        nodes = emptyList(),
        isTargetApp = true,
        isSupportedEventType = true,
    )
}

private class FakeTokenStore(initialToken: String?) : UploadTokenStore {
    private var token = initialToken
    var getCalls = 0
    var revokeCalls = 0
    var reauthenticationRequired = false

    override suspend fun getToken(): String? {
        getCalls += 1
        return token
    }

    override suspend fun revokeToken() {
        revokeCalls += 1
        token = null
        reauthenticationRequired = true
    }
}

private class FakeQueue(
    private val batch: QueuedCaptureBatch,
    private val acknowledgeResult: AcknowledgeResult = AcknowledgeResult.FullyAcknowledged,
) : UploadEventQueue {
    var peekCalls = 0
    val peekSizes = mutableListOf<Int>()
    var acknowledgeCalls = 0

    override suspend fun peekBatch(maxCount: Int): QueuedCaptureBatch {
        peekCalls += 1
        peekSizes += maxCount
        return batch
    }

    override suspend fun acknowledge(token: QueueBatchToken): AcknowledgeResult {
        acknowledgeCalls += 1
        return acknowledgeResult
    }
}

private class FakeClient(
    private val response: suspend (String, List<QueuedCapture>) -> CapturesApiResult,
) : UploadCapturesClient {
    constructor(result: CapturesApiResult) : this({ _, _ -> result })

    var calls = 0
    var lastCaptures: List<QueuedCapture> = emptyList()

    override suspend fun sendCaptures(
        token: String,
        captures: List<QueuedCapture>,
    ): CapturesApiResult {
        calls += 1
        lastCaptures = captures
        return response(token, captures)
    }
}
