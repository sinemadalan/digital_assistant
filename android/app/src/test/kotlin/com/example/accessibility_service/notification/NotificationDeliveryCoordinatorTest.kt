package com.example.accessibility_service.notification

import com.example.accessibility_service.networking.NotificationSeverity
import com.example.accessibility_service.networking.NotificationsApiResult
import com.example.accessibility_service.networking.ServerNotification
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class NotificationDeliveryCoordinatorTest {
    @Test fun savesWholeBatchBeforeAnyDisplayAndAcknowledgesOnlyAfterDurableDisplay() = runTest {
        val f = Fixture()
        val a = incoming(1)
        val b = incoming(2)
        val result = f.coordinator.processFetched("token", listOf(a, b))
        assertEquals(NotificationDeliveryResult(NotificationDeliveryStatus.COMPLETED, 2, 2), result)
        assertEquals(listOf("save:2", "show:1", "displayed:1", "ack:1", "done:1",
            "show:2", "displayed:2", "ack:2", "done:2"), f.events)
    }

    @Test fun permissionOrChannelBlockLeavesRowsPendingWithoutAck() = runTest {
        val f = Fixture()
        f.canShow = false
        val result = f.coordinator.processFetched("token", listOf(incoming(1)))
        assertEquals(NotificationDeliveryStatus.DISPLAY_BLOCKED, result.status)
        assertEquals(listOf("save:1", "show:1"), f.events)
        assertEquals(1, f.store.pendingDisplay().size)
        f.canShow = true
        assertEquals(1, f.coordinator.resumePending("token").acknowledged)
    }

    @Test fun presenterExceptionDoesNotConsumeOrAcknowledgeRow() = runTest {
        val f = Fixture()
        f.showFailure = IllegalStateException("OS unavailable")
        assertEquals(NotificationDeliveryStatus.DISPLAY_BLOCKED,
            f.coordinator.processFetched("token", listOf(incoming(1))).status)
        assertTrue(f.store.pendingAcknowledgements().isEmpty())
        assertEquals(1, f.store.pendingDisplay().size)
    }

    @Test fun retryableAckResumesWithoutRedisplayAndPropagatesRetryAfter() = runTest {
        val f = Fixture()
        f.ackResponse = NotificationsApiResult.RetryableHttpError(429, 45_000)
        val result = f.coordinator.processFetched("token", listOf(incoming(1), incoming(2)))
        assertEquals(NotificationDeliveryStatus.RETRYABLE_FAILURE, result.status)
        assertEquals(45_000L, result.retryAfterMillis)
        assertEquals(1, f.store.pendingAcknowledgements().size)
        assertEquals(1, f.store.pendingDisplay().size)
        f.ackResponse = NotificationsApiResult.Success(Unit)
        f.coordinator.resumePending("token")
        assertEquals(1, f.events.count { it == "show:1" })
        assertEquals(1, f.events.count { it == "show:2" })
        assertEquals(2, f.events.count { it == "ack:1" })
    }

    @Test fun ack404IsTerminalAndDoesNotReappearOnDuplicateBatch() = runTest {
        val f = Fixture()
        f.ackResponse = NotificationsApiResult.NotFound
        assertEquals(1, f.coordinator.processFetched("token", listOf(incoming(1))).notFound)
        f.coordinator.processFetched("token", listOf(incoming(1)))
        assertEquals(1, f.events.count { it == "show:1" })
        assertEquals(1, f.events.count { it == "ack:1" })
        assertTrue(f.store.pendingAcknowledgements().isEmpty())
    }

    @Test fun unauthorizedStopsFurtherDeliveryAndLeavesWorkDurable() = runTest {
        val f = Fixture()
        f.ackResponse = NotificationsApiResult.Unauthorized
        val result = f.coordinator.processFetched("token", listOf(incoming(1), incoming(2)))
        assertEquals(NotificationDeliveryStatus.UNAUTHORIZED, result.status)
        assertFalse(f.events.contains("show:2"))
        assertEquals(1, f.store.pendingAcknowledgements().size)
        assertEquals(1, f.store.pendingDisplay().size)
    }

    @Test fun saveFailurePreventsAllPresentationAndAck() = runTest {
        val f = Fixture()
        f.store.failSave = true
        assertEquals(NotificationDeliveryStatus.STORAGE_ERROR,
            f.coordinator.processFetched("token", listOf(incoming(1))).status)
        assertEquals(listOf("save:1"), f.events)
    }

    @Test fun failureAfterOsDeliveryDoesNotAckAndRetryUsesSameAndroidId() = runTest {
        val f = Fixture()
        f.store.failMarkDisplayed = true
        assertEquals(NotificationDeliveryStatus.STORAGE_ERROR,
            f.coordinator.processFetched("token", listOf(incoming(1))).status)
        assertFalse(f.events.contains("ack:1"))
        f.store.failMarkDisplayed = false
        f.coordinator.resumePending("token")
        assertEquals(2, f.shownAndroidIds.size)
        assertEquals(f.shownAndroidIds.first(), f.shownAndroidIds.last())
    }

    @Test fun failureSavingAckSuccessRetriesAckWithoutRedisplay() = runTest {
        val f = Fixture()
        f.store.failMarkAcknowledged = true
        assertEquals(NotificationDeliveryStatus.STORAGE_ERROR,
            f.coordinator.processFetched("token", listOf(incoming(1))).status)
        f.store.failMarkAcknowledged = false
        f.coordinator.resumePending("token")
        assertEquals(1, f.events.count { it == "show:1" })
        assertEquals(2, f.events.count { it == "ack:1" })
    }

    @Test fun missingTokenStillSavesFetchedBatchButHasNoOsOrHttpSideEffects() = runTest {
        val f = Fixture()
        assertEquals(NotificationDeliveryStatus.NO_VALID_TOKEN,
            f.coordinator.processFetched(null, listOf(incoming(1))).status)
        assertEquals(listOf("save:1"), f.events)
        assertEquals(1, f.store.pendingDisplay().size)
    }

    @Test fun clientProtocolErrorsAndTimeoutRetainAckForLaterAttempt() = runTest {
        for ((response, status) in listOf(
            NotificationsApiResult.ClientHttpError(403) to NotificationDeliveryStatus.CLIENT_ERROR,
            NotificationsApiResult.InvalidResponse to NotificationDeliveryStatus.CLIENT_ERROR,
            NotificationsApiResult.Timeout to NotificationDeliveryStatus.RETRYABLE_FAILURE,
            NotificationsApiResult.NetworkError to NotificationDeliveryStatus.RETRYABLE_FAILURE,
        )) {
            val f = Fixture()
            f.ackResponse = response
            assertEquals(status, f.coordinator.processFetched("token", listOf(incoming(1))).status)
            assertEquals(1, f.events.count { it == "ack:1" })
            assertEquals(1, f.store.pendingAcknowledgements().size)
        }
    }

    @Test fun cancellationPropagatesAndUnlocksCoordinatorForNextCall() = runTest {
        val f = Fixture()
        f.showFailure = CancellationException()
        try {
            f.coordinator.processFetched("token", listOf(incoming(1)))
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        f.showFailure = null
        assertEquals(NotificationDeliveryStatus.COMPLETED, f.coordinator.resumePending("token").status)
    }

    @Test fun overlappingFetchedBatchesAreSerializedWithoutDroppingEitherBatch() = runTest {
        val f = Fixture()
        (1..5).map { index ->
            async { f.coordinator.processFetched("token", listOf(incoming(index))) }
        }.awaitAll()
        assertEquals(5, f.events.count { it.startsWith("done:") })
        assertEquals(5, f.shownAndroidIds.distinct().size)
    }

    @Test fun expiryDuringProcessingPreventsOsDeliveryAndAck() = runTest {
        val f = Fixture()
        val coordinator = NotificationDeliveryCoordinator(
            f.store,
            StoredNotificationPresenter { fail("Expired row must not be shown"); true },
            NotificationAcknowledger { _, _ -> fail("Expired row must not be acked"); NotificationsApiResult.Success(Unit) },
            nowMillis = { Long.MAX_VALUE },
        )
        assertEquals(NotificationDeliveryStatus.COMPLETED,
            coordinator.processFetched("token", listOf(incoming(1))).status)
        assertEquals(listOf("save:1"), f.events)
    }


    @Test fun changedSessionSavesFetchedDataWithoutOsOrAckSideEffects() = runTest {
        val f = Fixture()
        val coordinator = NotificationDeliveryCoordinator(
            f.store,
            StoredNotificationPresenter { fail("Stale session must not display"); true },
            NotificationAcknowledger { _, _ -> fail("Stale session must not ack"); NotificationsApiResult.Success(Unit) },
            sessionIsCurrent = { false },
        )
        assertEquals(NotificationDeliveryStatus.SESSION_CHANGED,
            coordinator.processFetched("token", listOf(incoming(1))).status)
        assertEquals(listOf("save:1"), f.events)
        assertEquals(1, f.store.pendingDisplay().size)
    }
    @Test fun staleDeviceHandleFailsClosed() = runTest {
        val f = Fixture()
        f.store.ownerChanged = true
        assertEquals(NotificationDeliveryStatus.OWNER_CHANGED, f.coordinator.resumePending("token").status)
        assertTrue(f.events.isEmpty())
    }
}

private class Fixture {
    val events = mutableListOf<String>()
    val store = MemoryDeliveryStore(events)
    var canShow = true
    var showFailure: Exception? = null
    val shownAndroidIds = mutableListOf<Int>()
    var ackResponse: NotificationsApiResult<Unit> = NotificationsApiResult.Success(Unit)
    val coordinator = NotificationDeliveryCoordinator(store, StoredNotificationPresenter {
        events.add("show:" + it.id)
        showFailure?.let { error -> throw error }
        shownAndroidIds.add(it.androidNotificationId)
        canShow
    }, NotificationAcknowledger { token, id ->
        assertEquals("token", token)
        events.add("ack:$id")
        kotlinx.coroutines.yield()
        ackResponse
    })
}

private class MemoryDeliveryStore(private val events: MutableList<String>) : DeliveryNotificationStore {
    private val rows = linkedMapOf<String, StoredNotification>()
    var failSave = false
    var failMarkDisplayed = false
    var failMarkAcknowledged = false
    var ownerChanged = false
    override suspend fun saveFetched(notifications: List<ServerNotification>) {
        events.add("save:" + notifications.size)
        if (failSave) throw IOException()
        for (n in notifications) rows.putIfAbsent(n.id, StoredNotification(
            n.id, 1_000_000 + rows.size, Long.MAX_VALUE, NotificationDeliveryState.READY_TO_DISPLAY, n,
        ))
    }
    override suspend fun pendingDisplay(): List<StoredNotification> {
        if (ownerChanged) throw NotificationStoreOwnerChangedException()
        return rows.values.filter { it.state == NotificationDeliveryState.READY_TO_DISPLAY }
    }
    override suspend fun pendingAcknowledgements(): List<StoredNotification> {
        if (ownerChanged) throw NotificationStoreOwnerChangedException()
        return rows.values.filter { it.state == NotificationDeliveryState.AWAITING_ACK }
    }
    override suspend fun markDisplayed(id: String): Boolean {
        if (failMarkDisplayed) throw IOException()
        events.add("displayed:$id")
        rows[id] = rows.getValue(id).copy(state = NotificationDeliveryState.AWAITING_ACK)
        return true
    }
    override suspend fun markAcknowledged(id: String): Boolean {
        if (failMarkAcknowledged) throw IOException()
        events.add("done:$id")
        rows[id] = rows.getValue(id).copy(state = NotificationDeliveryState.ACKNOWLEDGED, notification = null)
        return true
    }
    override suspend fun markAcknowledgementNotFound(id: String): Boolean {
        events.add("notfound:$id")
        rows[id] = rows.getValue(id).copy(state = NotificationDeliveryState.ACK_NOT_FOUND, notification = null)
        return true
    }
}

private fun incoming(index: Int) = ServerNotification(
    id = index.toString(), category = "hakaret", severity = NotificationSeverity.HIGH,
    title = "Generic title", body = "Generic body", createdAt = "2026-10-22T09:00:00Z", isTest = false,
)
