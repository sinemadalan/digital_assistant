package com.example.accessibility_service.upload

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Prevents concurrent pipeline initialization and permits a delayed retry after failure. */
internal class PipelineInitializationRetryGate(
    private val scope: CoroutineScope,
    private val initialize: () -> Unit,
    private val pipelineLogger: (String) -> Unit = {},
    private val retryDelayMillis: suspend (Long) -> Unit = { delay(it) },
) {
    private val started = AtomicBoolean(false)
    private var retryJob: Job? = null
    private var failureSeen = false

    fun tryStart(): Boolean = started.compareAndSet(false, true)

    fun initializationSucceeded() {
        retryJob?.cancel()
        retryJob = null
        if (failureSeen) {
            failureSeen = false
            pipelineLogger("Phase5A: pipeline initialization recovered")
        }
    }

    fun initializationFailed() {
        pipelineLogger("Phase5A: pipeline initialization failed")
        failureSeen = true
        started.set(false)
        if (retryJob?.isActive == true) return
        pipelineLogger("Phase5A: pipeline initialization retry scheduled")
        retryJob = scope.launch {
            retryDelayMillis(INITIALIZATION_RETRY_DELAY_MS)
            retryJob = null
            if (tryStart()) initialize()
        }
    }

    fun close() {
        retryJob?.cancel()
        retryJob = null
    }

    internal companion object {
        const val INITIALIZATION_RETRY_DELAY_MS = 5_000L
    }
}
