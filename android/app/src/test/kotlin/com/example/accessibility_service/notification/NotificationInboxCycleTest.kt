package com.example.accessibility_service.notification

import com.example.accessibility_service.networking.NotificationSeverity
import com.example.accessibility_service.networking.NotificationsApiResult
import com.example.accessibility_service.networking.ServerNotification
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class NotificationInboxCycleTest {
    @Test fun recoversLocalWorkAndChecksCapacityBeforeEveryPageOfFullDrain() = runTest {
        val f = CycleFixture()
        f.responses.add(NotificationsApiResult.Success(List(100) { notification() }))
        f.responses.add(NotificationsApiResult.Success(emptyList()))
        assertEquals(NotificationPollStatus.COMPLETE, f.cycle.run().status)
        assertEquals(listOf("cleanup", "resume", "capacity", "fetch", "deliver:100",
            "capacity", "fetch", "deliver:0"), f.events)
    }

    @Test fun capacityFailureStopsBeforeNextConsumingGet() = runTest {
        val f = CycleFixture()
        f.responses.add(NotificationsApiResult.Success(List(100) { notification() }))
        f.afterDelivery = { f.capacity = false }
        assertEquals(NotificationPollStatus.NO_CAPACITY, f.cycle.run().status)
        assertEquals(1, f.events.count { it == "fetch" })
    }

    @Test fun permissionBlockDoesNotConsumeServerRowsButStillAttemptsSavedReceipts() = runTest {
        val f = CycleFixture()
        f.presentation = false
        assertEquals(NotificationPollStatus.DISPLAY_BLOCKED, f.cycle.run().status)
        assertEquals(listOf("cleanup", "resume"), f.events)
    }

    @Test fun savedAckFailureDefersNewFetchAndPropagatesDelay() = runTest {
        val f = CycleFixture()
        f.resumeResult = NotificationDeliveryResult(NotificationDeliveryStatus.RETRYABLE_FAILURE, retryAfterMillis = 80_000)
        assertEquals(NotificationPollResult(NotificationPollStatus.RETRY, 80_000), f.cycle.run())
        assertFalse(f.events.contains("fetch"))
    }

    @Test fun changedSessionAfterGetStillPersistsResponseAndStopsSideEffects() = runTest {
        val f = CycleFixture()
        f.responses.add(NotificationsApiResult.Success(listOf(notification())))
        f.deliveryResult = NotificationDeliveryResult(NotificationDeliveryStatus.SESSION_CHANGED)
        assertEquals(NotificationPollStatus.SESSION_CHANGED, f.cycle.run().status)
        assertEquals(listOf("cleanup", "resume", "capacity", "fetch", "deliver:1"), f.events)
    }

    @Test fun missingSessionStopsBeforeAccessingLocalInbox() = runTest {
        val f = CycleFixture()
        f.current = false
        assertEquals(NotificationPollStatus.SESSION_CHANGED, f.cycle.run().status)
        assertTrue(f.events.isEmpty())
    }

    @Test fun fetchErrorsAreClassifiedWithoutDeliveringPartialData() = runTest {
        for ((response, expected) in listOf(
            NotificationsApiResult.Unauthorized to NotificationPollResult(NotificationPollStatus.UNAUTHORIZED),
            NotificationsApiResult.RetryableHttpError(429, 75_000) to NotificationPollResult(NotificationPollStatus.RETRY, 75_000),
            NotificationsApiResult.Timeout to NotificationPollResult(NotificationPollStatus.RETRY),
            NotificationsApiResult.NetworkError to NotificationPollResult(NotificationPollStatus.RETRY),
            NotificationsApiResult.InvalidResponse to NotificationPollResult(NotificationPollStatus.CLIENT_ERROR),
            NotificationsApiResult.NotFound to NotificationPollResult(NotificationPollStatus.CLIENT_ERROR),
            NotificationsApiResult.ClientHttpError(403) to NotificationPollResult(NotificationPollStatus.CLIENT_ERROR),
        )) {
            val f = CycleFixture()
            f.responses.add(response)
            assertEquals(expected, f.cycle.run())
            assertFalse(f.events.any { it.startsWith("deliver:") })
        }
    }

    @Test fun reportsRecoveredAndNewDeliveryReceiptCountsSeparately() = runTest {
        val f = CycleFixture()
        f.resumeResult = NotificationDeliveryResult(NotificationDeliveryStatus.COMPLETED, acknowledged = 2)
        f.deliveryResult = NotificationDeliveryResult(NotificationDeliveryStatus.COMPLETED, displayed = 1, acknowledged = 1)
        f.responses.add(NotificationsApiResult.Success(listOf(notification())))
        assertEquals(NotificationPollStatus.COMPLETE, f.cycle.run().status)
        assertEquals(listOf(f.resumeResult, f.deliveryResult), f.deliveryReports)
    }

    @Test fun unauthorizedAckOrStorageFailureNeverStartsFetch() = runTest {
        for ((status, expected) in listOf(
            NotificationDeliveryStatus.UNAUTHORIZED to NotificationPollStatus.UNAUTHORIZED,
            NotificationDeliveryStatus.STORAGE_ERROR to NotificationPollStatus.STORAGE_ERROR,
            NotificationDeliveryStatus.CLIENT_ERROR to NotificationPollStatus.CLIENT_ERROR,
        )) {
            val f = CycleFixture()
            f.resumeResult = NotificationDeliveryResult(status)
            assertEquals(expected, f.cycle.run().status)
            assertFalse(f.events.contains("fetch"))
        }
    }
}

private class CycleFixture {
    val events = mutableListOf<String>()
    val deliveryReports = mutableListOf<NotificationDeliveryResult>()
    val responses = ArrayDeque<NotificationsApiResult<List<ServerNotification>>>()
    var capacity = true
    var presentation = true
    var current = true
    var afterDelivery: () -> Unit = {}
    var resumeResult = NotificationDeliveryResult(NotificationDeliveryStatus.COMPLETED)
    var deliveryResult = NotificationDeliveryResult(NotificationDeliveryStatus.COMPLETED)
    val cycle = NotificationInboxCycle(
        canFetch = { events.add("capacity"); capacity },
        cleanup = { events.add("cleanup"); Unit },
        resume = { events.add("resume"); resumeResult },
        fetch = { events.add("fetch"); responses.removeFirst() },
        deliver = { events.add("deliver:" + it.size); afterDelivery(); deliveryResult },
        canPresent = { presentation },
        isCurrent = { current },
        reportDelivery = { deliveryReports.add(it) },
    )
}

private fun notification() = ServerNotification(
    "019a0a45-7000-7000-8000-000000000001", "hakaret", NotificationSeverity.HIGH,
    "GiDA", "Generic notification.", "2026-10-22T09:00:00Z", false,
)
