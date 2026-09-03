package com.example.accessibility_service.upload

import com.example.accessibility_service.persistence.EnqueueResult
import com.example.accessibility_service.persistence.PersistentEventQueue
import com.example.accessibility_service.persistence.QueuedCapture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Serializes accessibility ingestion and owns event-driven upload triggers. */
internal class CaptureQueueBridge(
    private val scope: CoroutineScope,
    private val queue: CaptureQueue,
    private val uploader: CaptureUploader,
    private val diagnosticLogger: (String) -> Unit,
    private val pipelineLogger: (String) -> Unit = {},
    private val delayMillis: suspend (Long) -> Unit = { delay(it) },
) : CaptureSink {
    constructor(
        scope: CoroutineScope,
        queue: PersistentEventQueue,
        uploader: UploadCoordinator,
        diagnosticLogger: (String) -> Unit,
        pipelineLogger: (String) -> Unit = {},
    ) : this(
        scope = scope,
        queue = PersistentCaptureQueue(queue),
        uploader = CoordinatorCaptureUploader(uploader),
        diagnosticLogger = diagnosticLogger,
        pipelineLogger = pipelineLogger,
    )

    private val commands = Channel<Command>(Channel.UNLIMITED)
    private var flushJob: Job? = null
    private var retryJob: Job? = null
    private var retryAttempt = 0
    private var batchSuppressedUntilFlush = false
    private val ingestionJob = scope.launch {
        for (command in commands) {
            when (command) {
                is Command.Enqueue -> processEnqueue(command)
                Command.ServiceStarted -> processServiceStart()
                Command.FlushExpired -> processUpload(TriggerSource.FLUSH)
                Command.RetryExpired -> processUpload(TriggerSource.RETRY)
                Command.AuthTokenAvailable -> processAuthTokenAvailable()
            }
        }
    }

    override fun enqueue(capture: QueuedCapture): Boolean =
        commands.trySend(Command.Enqueue(capture)).isSuccess

    fun onServiceStarted(): Boolean = commands.trySend(Command.ServiceStarted).isSuccess

    fun onAuthTokenAvailable(): Boolean = commands.trySend(Command.AuthTokenAvailable).isSuccess

    fun close() {
        flushJob?.cancel()
        flushJob = null
        retryJob?.cancel()
        retryJob = null
        commands.close()
        ingestionJob.cancel()
    }

    private suspend fun processEnqueue(command: Command.Enqueue) {
        val result = try {
            queue.enqueue(command.capture)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            diagnosticLogger("Persistent capture enqueue failed: ${error.javaClass.simpleName}")
            return
        }

        when (result) {
            is EnqueueResult.Oversized -> {
                diagnosticLogger(
                    "Persistent capture was too large: recordBytes=${result.recordBytes}, " +
                        "capacityBytes=${result.usableCapacityBytes}",
                )
            }
            is EnqueueResult.Enqueued -> {
                pipelineLogger(
                    "Phase5A: queued sequence=${result.sequence}, " +
                        "pending=${result.pendingPhysicalRecordCount}",
                )
                if (result.droppedOldestCount > 0) {
                    diagnosticLogger("Persistent capture queue dropped ${result.droppedOldestCount} oldest record(s)")
                }
                if (result.pendingPhysicalRecordCount >= uploader.runtimeConfig.batchSize &&
                    !batchSuppressedUntilFlush
                ) {
                    pipelineLogger(
                        "Phase5A: upload trigger=BATCH, pending=${result.pendingPhysicalRecordCount}, " +
                            "threshold=${uploader.runtimeConfig.batchSize}",
                    )
                    cancelFlush()
                    processUpload(TriggerSource.BATCH)
                } else if (!batchSuppressedUntilFlush) {
                    scheduleFlushIfNeeded()
                }
            }
        }
    }

    private suspend fun processServiceStart() {
        val pending = try {
            queue.startupPendingCount()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            diagnosticLogger("Persistent capture startup scan failed: ${error.javaClass.simpleName}")
            return
        }
        if (pending > 0) {
            pipelineLogger("Phase5A: upload trigger=STARTUP, pending=$pending")
            processUpload(TriggerSource.STARTUP)
        } else {
            pipelineLogger("Phase5A: startup queue empty")
        }
    }

    private suspend fun processAuthTokenAvailable() {
        val pending = readPendingCount() ?: return
        if (pending > 0) {
            pipelineLogger("Phase5A: upload trigger=REAUTH, pending=$pending")
            processUpload(TriggerSource.REAUTH)
        }
    }

    private suspend fun processUpload(source: TriggerSource) {
        if (source == TriggerSource.FLUSH) batchSuppressedUntilFlush = false
        if (source != TriggerSource.FLUSH) cancelFlush()
        val outcome = try {
            uploader.requestUpload()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            diagnosticLogger("Capture upload trigger failed: ${error.javaClass.simpleName}")
            if (source != TriggerSource.FLUSH) {
                batchSuppressedUntilFlush = true
                scheduleFlushIfNeeded()
            }
            return
        }

        val pending = readPendingCount() ?: return
        if (outcome is UploadOutcome.Uploaded) {
            pipelineLogger(
                "Phase5A: acknowledged=${outcome.accepted + outcome.skipped}, remaining=$pending",
            )
            resetRetryBackoffAfterSuccess()
        }
        if (pending == 0 || outcome is UploadOutcome.QueueEmpty) {
            batchSuppressedUntilFlush = false
            cancelFlush()
            cancelRetry(resetBackoff = true)
            return
        }


        when (outcome) {
            is UploadOutcome.Uploaded -> Unit
            is UploadOutcome.RetryableFailure -> {
                cancelFlush()
                batchSuppressedUntilFlush = true
                scheduleRetryIfNeeded()
                return
            }
            UploadOutcome.TokenRevoked,
            UploadOutcome.NoValidToken,
            is UploadOutcome.InvalidResponse,
            is UploadOutcome.OtherHttpFailure,
            is UploadOutcome.InvalidRequest,
            -> {
                cancelFlush()
                cancelRetry(resetBackoff = false)
                batchSuppressedUntilFlush = true
                return
            }
            else -> Unit
        }

        val queueProgressed = outcome is UploadOutcome.Uploaded ||
            outcome is UploadOutcome.BatchDiscardedUnprocessable
        if (source != TriggerSource.FLUSH && !queueProgressed) {
            // One later flush may retry, while burst events above threshold cannot create an upload storm.
            batchSuppressedUntilFlush = true
        }

        val shouldSchedule = when (source) {
            TriggerSource.BATCH,
            TriggerSource.STARTUP,
            TriggerSource.REAUTH,
            TriggerSource.RETRY,
            -> true
            TriggerSource.FLUSH -> outcome is UploadOutcome.Uploaded ||
                outcome is UploadOutcome.BatchDiscardedUnprocessable ||
                outcome is UploadOutcome.AlreadyRunning
        }
        if (shouldSchedule) scheduleFlushIfNeeded()
    }

    private fun scheduleFlushIfNeeded() {
        if (flushJob?.isActive == true) return
        val delayMs = uploader.runtimeConfig.flushSeconds.toLong() * 1_000L
        pipelineLogger("Phase5A: flush scheduled in ${uploader.runtimeConfig.flushSeconds}s")
        flushJob = scope.launch {
            delayMillis(delayMs)
            flushJob = null
            pipelineLogger("Phase5A: upload trigger=FLUSH")
            commands.send(Command.FlushExpired)
        }
    }

    private fun cancelFlush() {
        flushJob?.cancel()
        flushJob = null
    }

    private fun scheduleRetryIfNeeded() {
        if (retryJob?.isActive == true) return
        val delaySeconds = retryDelaySeconds(retryAttempt)
        retryAttempt += 1
        pipelineLogger("Phase5A: retry scheduled in ${delaySeconds}s")
        retryJob = scope.launch {
            delayMillis(delaySeconds * 1_000L)
            retryJob = null
            pipelineLogger("Phase5A: upload trigger=RETRY")
            commands.send(Command.RetryExpired)
        }
    }

    private fun resetRetryBackoffAfterSuccess() {
        if (retryAttempt == 0 && retryJob?.isActive != true) return
        cancelRetry(resetBackoff = true)
        pipelineLogger("Phase5A: retry backoff reset after success")
    }

    private fun cancelRetry(resetBackoff: Boolean) {
        retryJob?.cancel()
        retryJob = null
        if (resetBackoff) retryAttempt = 0
    }

    private suspend fun readPendingCount(): Int? = try {
        queue.currentPhysicalRecordCount()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        diagnosticLogger("Persistent capture count read failed: ${error.javaClass.simpleName}")
        null
    }

    private fun retryDelaySeconds(attempt: Int): Long = when (attempt) {
        0 -> 20L
        1 -> 40L
        2 -> 80L
        3 -> 160L
        else -> 300L
    }

    private sealed interface Command {
        data class Enqueue(val capture: QueuedCapture) : Command
        data object ServiceStarted : Command
        data object FlushExpired : Command
        data object RetryExpired : Command
        data object AuthTokenAvailable : Command
    }

    private enum class TriggerSource { BATCH, FLUSH, STARTUP, RETRY, REAUTH }
}

internal interface CaptureQueue {
    suspend fun enqueue(capture: QueuedCapture): EnqueueResult
    suspend fun startupPendingCount(): Int
    suspend fun currentPhysicalRecordCount(): Int
}

internal interface CaptureUploader {
    val runtimeConfig: UploadRuntimeConfig
    suspend fun requestUpload(): UploadOutcome
}

private class PersistentCaptureQueue(private val delegate: PersistentEventQueue) : CaptureQueue {
    override suspend fun enqueue(capture: QueuedCapture): EnqueueResult = delegate.enqueue(capture)
    override suspend fun startupPendingCount(): Int = delegate.pendingCount()
    override suspend fun currentPhysicalRecordCount(): Int = delegate.currentPhysicalRecordCount()
}

private class CoordinatorCaptureUploader(private val delegate: UploadCoordinator) : CaptureUploader {
    override val runtimeConfig: UploadRuntimeConfig get() = delegate.runtimeConfig
    override suspend fun requestUpload(): UploadOutcome = delegate.requestUpload()
}
