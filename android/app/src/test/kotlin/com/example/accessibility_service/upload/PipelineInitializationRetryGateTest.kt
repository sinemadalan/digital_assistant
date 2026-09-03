package com.example.accessibility_service.upload

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PipelineInitializationRetryGateTest {
    @Test
    fun failureMakesInitializationRetryableAndRecoveryIsReported() = runTest {
        val logs = mutableListOf<String>()
        var retryCalls = 0
        val gate = PipelineInitializationRetryGate(
            scope = this,
            initialize = { retryCalls += 1 },
            pipelineLogger = logs::add,
        )

        assertTrue(gate.tryStart())
        assertFalse(gate.tryStart())
        gate.initializationFailed()
        gate.initializationFailed()
        runCurrent()
        assertEquals(0, retryCalls)

        advanceTimeBy(PipelineInitializationRetryGate.INITIALIZATION_RETRY_DELAY_MS)
        runCurrent()
        assertEquals(1, retryCalls)
        assertFalse(gate.tryStart())

        gate.initializationSucceeded()
        assertTrue(logs.contains("Phase5A: pipeline initialization retry scheduled"))
        assertTrue(logs.contains("Phase5A: pipeline initialization recovered"))
        gate.close()
    }
}
