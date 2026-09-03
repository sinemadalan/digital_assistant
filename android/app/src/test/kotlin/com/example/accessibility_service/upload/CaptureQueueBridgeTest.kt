package com.example.accessibility_service.upload

import com.example.accessibility_service.persistence.EnqueueResult
import com.example.accessibility_service.persistence.QueuedCapture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureQueueBridgeTest {
    @Test
    fun enqueueIsFifoAndPersistencePrecedesLegacyDispatch() = runTest {
        val operations = mutableListOf<String>()
        val queue = FakeCaptureQueue { capture -> operations += "queue:${capture.appName}" }
        val bridge = bridge(queue)

        bridge.enqueue(capture("A")) { operations += "network:A" }
        bridge.enqueue(capture("B")) { operations += "network:B" }
        runCurrent()

        assertEquals(listOf("A", "B"), queue.captures.map { it.appName })
        assertEquals(listOf("queue:A", "network:A", "queue:B", "network:B"), operations)
        bridge.close()
    }

    @Test
    fun enqueueFailureIsContainedAndDoesNotInvokeNetworkOrUpload() = runTest {
        val logs = mutableListOf<String>()
        val uploader = FakeCaptureUploader()
        val queue = FakeCaptureQueue().apply { enqueueFailure = IllegalStateException("disk") }
        var legacyCalled = false
        val bridge = bridge(queue, uploader, logs)

        bridge.enqueue(capture("A")) { legacyCalled = true }
        runCurrent()

        assertFalse(legacyCalled)
        assertEquals(0, uploader.calls)
        assertTrue(logs.single().contains("IllegalStateException"))
        bridge.close()
    }

    @Test
    fun belowBatchSchedulesOneShotAndThresholdRequestsUploadWithoutStartupScan() = runTest {
        val queue = FakeCaptureQueue()
        val uploader = FakeCaptureUploader(config = UploadRuntimeConfig(batchSize = 2, flushSeconds = 20)) {
            queue.recordCount = 0
            UploadOutcome.Uploaded(2, 0, com.example.accessibility_service.persistence.AcknowledgeResult.FullyAcknowledged)
        }
        val bridge = bridge(queue, uploader)

        bridge.enqueue(capture("A"))
        runCurrent()
        assertEquals(0, uploader.calls)
        bridge.enqueue(capture("B"))
        runCurrent()

        assertEquals(1, uploader.calls)
        assertEquals(0, queue.startupScanCalls)
        bridge.close()
    }

    @Test
    fun currentRuntimeBatchSizeIsReadForEveryEnqueue() = runTest {
        val queue = FakeCaptureQueue()
        val uploader = FakeCaptureUploader(config = UploadRuntimeConfig(3, 20))
        val bridge = bridge(queue, uploader)

        bridge.enqueue(capture("A"))
        runCurrent()
        uploader.config = UploadRuntimeConfig(2, 20)
        bridge.enqueue(capture("B"))
        runCurrent()

        assertEquals(1, uploader.calls)
        bridge.close()
    }

    @Test
    fun noTokenDoesNotStormAndWaitsForReauthSignal() = runTest {
        val queue = FakeCaptureQueue()
        val uploader = FakeCaptureUploader(config = UploadRuntimeConfig(2, 5)) { UploadOutcome.NoValidToken }
        val bridge = bridge(queue, uploader)

        bridge.enqueue(capture("A"))
        bridge.enqueue(capture("B"))
        bridge.enqueue(capture("C"))
        bridge.enqueue(capture("D"))
        runCurrent()
        assertEquals(1, uploader.calls)

        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, uploader.calls)

        bridge.enqueue(capture("E"))
        runCurrent()
        assertEquals(1, uploader.calls)
        bridge.onAuthTokenAvailable()
        runCurrent()
        assertEquals(2, uploader.calls)
        bridge.close()
    }

    @Test
    fun firstPendingEventSchedulesOneShotAndFailureDoesNotBecomePollingLoop() = runTest {
        val queue = FakeCaptureQueue()
        val uploader = FakeCaptureUploader(config = UploadRuntimeConfig(30, 20)) {
            UploadOutcome.NoValidToken
        }
        val bridge = bridge(queue, uploader)

        bridge.enqueue(capture("A"))
        runCurrent()
        advanceTimeBy(19_999)
        runCurrent()
        assertEquals(0, uploader.calls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, uploader.calls)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, uploader.calls)
        bridge.close()
    }

    @Test
    fun noTokenSuppressesNewFlushSchedulesUntilReauth() = runTest {
        val queue = FakeCaptureQueue()
        val uploader = FakeCaptureUploader(config = UploadRuntimeConfig(30, 20)) { UploadOutcome.NoValidToken }
        val bridge = bridge(queue, uploader)

        bridge.enqueue(capture("A"))
        runCurrent()
        advanceTimeBy(20_000)
        runCurrent()
        uploader.config = UploadRuntimeConfig(30, 5)
        bridge.enqueue(capture("B"))
        runCurrent()
        advanceTimeBy(4_999)
        runCurrent()
        assertEquals(1, uploader.calls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, uploader.calls)
        bridge.close()
    }

    @Test
    fun serviceStartScansOnceAndOnlyUploadsWhenPending() = runTest {
        val emptyQueue = FakeCaptureQueue()
        val emptyUploader = FakeCaptureUploader()
        val emptyBridge = bridge(emptyQueue, emptyUploader)
        emptyBridge.onServiceStarted()
        runCurrent()
        assertEquals(1, emptyQueue.startupScanCalls)
        assertEquals(0, emptyUploader.calls)
        emptyBridge.close()

        val pendingQueue = FakeCaptureQueue().apply { recordCount = 1 }
        val pendingUploader = FakeCaptureUploader { UploadOutcome.NoValidToken }
        val pendingBridge = bridge(pendingQueue, pendingUploader)
        pendingBridge.onServiceStarted()
        runCurrent()
        assertEquals(1, pendingUploader.calls)
        assertEquals(1, pendingQueue.recordCount)
        pendingBridge.close()
    }

    @Test
    fun closeCancelsScheduledFlush() = runTest {
        val uploader = FakeCaptureUploader()
        val bridge = bridge(FakeCaptureQueue(), uploader)
        bridge.enqueue(capture("A"))
        runCurrent()

        bridge.close()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(0, uploader.calls)
    }

    @Test
    fun unauthorizedAndNoTokenDoNotScheduleRetry() = runTest {
        for (outcome in listOf(UploadOutcome.TokenRevoked, UploadOutcome.NoValidToken)) {
            val queue = FakeCaptureQueue().apply { recordCount = 1 }
            val uploader = FakeCaptureUploader { outcome }
            val bridge = bridge(queue, uploader)

            bridge.onServiceStarted()
            runCurrent()
            advanceTimeBy(600_000)
            runCurrent()

            assertEquals(1, uploader.calls)
            assertEquals(1, queue.recordCount)
            bridge.close()
        }
    }

    @Test
    fun reauthUploadsPendingQueueAndAcknowledgesOnSuccess() = runTest {
        val pipelineLogs = mutableListOf<String>()
        val queue = FakeCaptureQueue().apply { recordCount = 1 }
        val uploader = FakeCaptureUploader {
            queue.recordCount = 0
            UploadOutcome.Uploaded(
                1,
                0,
                com.example.accessibility_service.persistence.AcknowledgeResult.FullyAcknowledged,
            )
        }
        val bridge = bridge(queue, uploader, pipelineLogs = pipelineLogs)

        bridge.onAuthTokenAvailable()
        runCurrent()

        assertEquals(1, uploader.calls)
        assertEquals(0, queue.recordCount)
        assertTrue(pipelineLogs.contains("Phase5A: upload trigger=REAUTH, pending=1"))
        bridge.close()
    }

    @Test
    fun retryableFailureUsesBackoffAndSuccessResetsIt() = runTest {
        val pipelineLogs = mutableListOf<String>()
        val queue = FakeCaptureQueue().apply { recordCount = 1 }
        val outcomes = ArrayDeque<UploadOutcome>().apply {
            add(UploadOutcome.RetryableFailure(RetryableFailureReason.NETWORK_ERROR))
            add(UploadOutcome.RetryableFailure(RetryableFailureReason.SERVICE_UNAVAILABLE))
            add(
                UploadOutcome.Uploaded(
                    1,
                    0,
                    com.example.accessibility_service.persistence.AcknowledgeResult.FullyAcknowledged,
                ),
            )
        }
        val uploader = FakeCaptureUploader {
            outcomes.removeFirst().also {
                if (it is UploadOutcome.Uploaded) queue.recordCount = 0
            }
        }
        val bridge = bridge(queue, uploader, pipelineLogs = pipelineLogs)

        bridge.onServiceStarted()
        runCurrent()
        assertTrue(pipelineLogs.contains("Phase5A: retry scheduled in 20s"))
        advanceTimeBy(20_000)
        runCurrent()
        assertTrue(pipelineLogs.contains("Phase5A: retry scheduled in 40s"))
        advanceTimeBy(40_000)
        runCurrent()

        assertEquals(3, uploader.calls)
        assertEquals(0, queue.recordCount)
        assertTrue(pipelineLogs.contains("Phase5A: retry backoff reset after success"))
        bridge.close()
    }

    @Test
    fun repeatedTriggersDoNotCreateMultipleRetryTimers() = runTest {
        val queue = FakeCaptureQueue().apply { recordCount = 1 }
        val uploader = FakeCaptureUploader {
            UploadOutcome.RetryableFailure(RetryableFailureReason.NETWORK_ERROR)
        }
        val bridge = bridge(queue, uploader)

        bridge.onServiceStarted()
        bridge.onServiceStarted()
        runCurrent()
        assertEquals(2, uploader.calls)
        advanceTimeBy(20_000)
        runCurrent()

        assertEquals(3, uploader.calls)
        bridge.close()
    }

    @Test
    fun retryFlushAndBatchTriggersRemainSerialized() = runTest {
        val queue = FakeCaptureQueue().apply { recordCount = 1 }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val uploader = object : CaptureUploader {
            override val runtimeConfig = UploadRuntimeConfig(batchSize = 2, flushSeconds = 1)
            var calls = 0
            var active = 0
            var maxActive = 0

            override suspend fun requestUpload(): UploadOutcome {
                calls += 1
                active += 1
                maxActive = maxOf(maxActive, active)
                entered.complete(Unit)
                release.await()
                active -= 1
                return UploadOutcome.NoValidToken
            }
        }
        val bridge = CaptureQueueBridge(this, queue, uploader, {})

        bridge.onServiceStarted()
        entered.await()
        bridge.enqueue(capture("A"))
        bridge.onAuthTokenAvailable()
        advanceTimeBy(1_000)
        release.complete(Unit)
        runCurrent()

        assertEquals(1, uploader.maxActive)
        assertTrue(uploader.calls >= 2)
        bridge.close()
    }

    private fun TestScope.bridge(
        queue: FakeCaptureQueue,
        uploader: FakeCaptureUploader = FakeCaptureUploader(),
        logs: MutableList<String> = mutableListOf(),
        pipelineLogs: MutableList<String> = mutableListOf(),
    ) = CaptureQueueBridge(
        scope = this,
        queue = queue,
        uploader = uploader,
        diagnosticLogger = logs::add,
        pipelineLogger = pipelineLogs::add,
    )

    private fun capture(name: String) = QueuedCapture(
        packageName = "com.example.$name",
        appName = name,
        eventType = "TYPE_WINDOW_CONTENT_CHANGED",
        capturedAtDevice = "2026-09-02T10:15:30.123+03:00",
        screenText = listOf(name),
        nodes = emptyList(),
        isTargetApp = true,
        isSupportedEventType = true,
    )
}

private class FakeCaptureQueue(
    private val onEnqueue: (QueuedCapture) -> Unit = {},
) : CaptureQueue {
    val captures = mutableListOf<QueuedCapture>()
    var recordCount = 0
    var startupScanCalls = 0
    var enqueueFailure: Exception? = null

    override suspend fun enqueue(capture: QueuedCapture): EnqueueResult {
        enqueueFailure?.let { throw it }
        captures += capture
        recordCount += 1
        onEnqueue(capture)
        return EnqueueResult.Enqueued(captures.size.toLong(), 0, recordCount)
    }

    override suspend fun startupPendingCount(): Int {
        startupScanCalls += 1
        return recordCount
    }

    override suspend fun currentPhysicalRecordCount(): Int = recordCount
}

private class FakeCaptureUploader(
    var config: UploadRuntimeConfig = UploadRuntimeConfig(30, 20),
    private val outcome: () -> UploadOutcome = { UploadOutcome.NoValidToken },
) : CaptureUploader {
    var calls = 0
    override val runtimeConfig: UploadRuntimeConfig get() = config

    override suspend fun requestUpload(): UploadOutcome {
        calls += 1
        return outcome()
    }
}
