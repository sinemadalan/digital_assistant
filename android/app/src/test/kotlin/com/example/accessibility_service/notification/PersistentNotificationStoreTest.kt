package com.example.accessibility_service.notification

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.Path.Companion.toPath
import com.example.accessibility_service.networking.NotificationSeverity
import com.example.accessibility_service.networking.ServerNotification
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PersistentNotificationStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val jobs = mutableListOf<Job>()
    private var now = Instant.parse("2026-10-22T09:00:00Z").toEpochMilli()

    @After
    fun closeStores() = kotlinx.coroutines.runBlocking {
        jobs.forEach { it.cancelAndJoin() }
    }

    @Test
    fun savesWholeBatchAndRestoresPayloadOrderAndStableAndroidIdsAfterReopen() = runTest {
        val first = open()
        val batch = listOf(notification(1), notification(2).copy(category = "nefret_soylemi", isTest = true))
        assertEquals(SaveNotificationsResult(2, 0, 0), first.store.saveFetched(batch))
        val rows = first.store.pendingDisplay()
        assertEquals(batch, rows.map { it.notification })
        assertEquals(2, rows.map { it.androidNotificationId }.distinct().size)
        first.close()

        val reopened = open(first.file)
        assertEquals(rows, reopened.store.pendingDisplay())
        assertEquals(SaveNotificationsResult(0, 2, 0), reopened.store.saveFetched(batch))
        assertEquals(rows, reopened.store.pendingDisplay())
    }

    @Test
    fun duplicateIdsWithinBatchAndLaterFetchCannotReplacePayloadOrExtendExpiry() = runTest {
        val session = open()
        val original = notification(1)
        assertEquals(SaveNotificationsResult(1, 1, 0), session.store.saveFetched(listOf(original, original)))
        val before = session.store.pendingDisplay().single()
        now += 60_000
        assertEquals(SaveNotificationsResult(0, 1, 0), session.store.saveFetched(listOf(
            original.copy(body = "changed", category = "future_category", createdAt = Instant.ofEpochMilli(now).toString()),
        )))
        assertEquals(before, session.store.pendingDisplay().single())
    }

    @Test
    fun successfulDisplayBecomesDurableAckRetryAndDuplicateDoesNotRedisplay() = runTest {
        val first = open()
        val n = notification(1)
        first.store.saveFetched(listOf(n))
        val androidId = first.store.pendingDisplay().single().androidNotificationId
        assertTrue(first.store.markDisplayed(n.id))
        assertTrue(first.store.markDisplayed(n.id))
        assertTrue(first.store.pendingDisplay().isEmpty())
        first.close()

        val reopened = open(first.file)
        reopened.store.saveFetched(listOf(n))
        assertTrue(reopened.store.pendingDisplay().isEmpty())
        repeat(3) {
            val row = reopened.store.pendingAcknowledgements().single()
            assertEquals(n, row.notification)
            assertEquals(androidId, row.androidNotificationId)
            assertEquals(NotificationDeliveryState.AWAITING_ACK, row.state)
        }
    }

    @Test
    fun ack204ErasesMessageAndRetainsDeduplicationAcrossRestart() = runTest {
        val session = open()
        val n = notification(1).copy(title = "unique-title", body = "unique-body")
        session.store.saveFetched(listOf(n))
        session.store.markDisplayed(n.id)
        assertTrue(session.store.markAcknowledged(n.id))
        assertTrue(session.store.markAcknowledged(n.id))
        assertTrue(session.store.markDisplayed(n.id))
        assertTrue(session.store.pendingDisplay().isEmpty())
        assertTrue(session.store.pendingAcknowledgements().isEmpty())
        assertEquals(1, session.store.stats().completed)
        assertFalse(session.file.readText().contains("unique-title"))
        assertFalse(session.file.readText().contains("unique-body"))
        session.close()

        val reopened = open(session.file)
        assertEquals(SaveNotificationsResult(0, 1, 0), reopened.store.saveFetched(listOf(n)))
        assertTrue(reopened.store.pendingDisplay().isEmpty())
        assertTrue(reopened.store.pendingAcknowledgements().isEmpty())
    }

    @Test
    fun ack404StopsRetryAndKeepsTerminalTombstone() = runTest {
        val session = open()
        val n = notification(1)
        session.store.saveFetched(listOf(n))
        session.store.markDisplayed(n.id)
        assertTrue(session.store.markAcknowledgementNotFound(n.id))
        assertTrue(session.store.markAcknowledgementNotFound(n.id))
        session.store.markAcknowledged(n.id) // A stale retry cannot overwrite the terminal outcome.
        assertTrue(session.store.pendingAcknowledgements().isEmpty())
        assertEquals(1, session.store.stats().completed)
        assertEquals(SaveNotificationsResult(0, 1, 0), session.store.saveFetched(listOf(n)))
        assertTrue(session.file.readText().contains("ACK_NOT_FOUND"))
        assertFalse(session.file.readText().contains("Generic notification"))
    }

    @Test
    fun ackBeforeDisplayIsRejectedAndUnknownOrExpiredIdsAreNotAcknowledged() = runTest {
        val session = open()
        val n = notification(1)
        session.store.saveFetched(listOf(n))
        expectFailure<IllegalStateException> { session.store.markAcknowledged(n.id) }
        expectFailure<IllegalStateException> { session.store.markAcknowledgementNotFound(n.id) }
        assertEquals(1, session.store.pendingDisplay().size)
        assertFalse(session.store.markDisplayed(id(99)))
        assertFalse(session.store.markAcknowledged(id(99)))
        now += NORMAL_NOTIFICATION_TTL_MILLIS
        assertFalse(session.store.markDisplayed(n.id))
        assertFalse(session.store.markAcknowledged(n.id))
    }

    @Test
    fun retentionUsesServerCreationAndNormalAndTestHaveDifferentLifetimes() = runTest {
        val session = open()
        val normal = notification(1, ageMillis = 6 * DAY)
        val test = notification(2, ageMillis = 23 * HOUR).copy(isTest = true)
        session.store.saveFetched(listOf(normal, test))
        val rows = session.store.pendingDisplay()
        assertEquals(now + DAY, rows.first().expiresAtMillis)
        assertEquals(now + HOUR, rows.last().expiresAtMillis)
        now += HOUR
        assertEquals(1, session.store.cleanup())
        assertEquals(listOf(normal.id), session.store.pendingDisplay().map { it.id })
        now += 23 * HOUR
        assertEquals(1, session.store.cleanup())
        assertEquals(0, session.store.stats().totalRecords)
    }

    @Test
    fun expiredRowsAreSkippedAndFutureTimestampCannotExtendRetentionBeyondReceiptTtl() = runTest {
        val session = open()
        val future = notification(3).copy(createdAt = Instant.ofEpochMilli(now + DAY).toString())
        val result = session.store.saveFetched(listOf(
            notification(1, NORMAL_NOTIFICATION_TTL_MILLIS),
            notification(2, TEST_NOTIFICATION_TTL_MILLIS).copy(isTest = true),
            future,
        ))
        assertEquals(SaveNotificationsResult(1, 0, 2), result)
        assertEquals(now + NORMAL_NOTIFICATION_TTL_MILLIS, session.store.pendingDisplay().single().expiresAtMillis)
    }

    @Test
    fun expiryRemovesEveryStateIncludingTombstonesOnOpen() = runTest {
        val first = open()
        val rows = (1..4).map { notification(it).copy(isTest = true) }
        first.store.saveFetched(rows)
        first.store.markDisplayed(rows[1].id)
        first.store.markDisplayed(rows[2].id)
        first.store.markAcknowledged(rows[2].id)
        first.store.markDisplayed(rows[3].id)
        first.store.markAcknowledgementNotFound(rows[3].id)
        first.close()
        now += TEST_NOTIFICATION_TTL_MILLIS
        val reopened = open(first.file)
        assertEquals(0, reopened.store.stats().totalRecords)
        assertTrue(reopened.store.pendingAcknowledgements().isEmpty())
    }

    @Test
    fun failedOrBlockedDisplayRemainsPendingUntilExplicitlyMarked() = runTest {
        val session = open()
        session.store.saveFetched(listOf(notification(1)))
        repeat(3) {
            assertEquals(1, session.store.pendingDisplay().size)
            assertTrue(session.store.pendingAcknowledgements().isEmpty())
        }
    }

    @Test
    fun recordCapacityRejectsWholeBatchAndNeverEvictsPendingOrCompletedIds() = runTest {
        val session = open(policy = NotificationStoragePolicy(maxRecords = 2))
        val n = notification(1)
        session.store.saveFetched(listOf(n))
        expectFailure<NotificationStoreCapacityException> {
            session.store.saveFetched(listOf(notification(2), notification(3)))
        }
        assertEquals(listOf(n), session.store.pendingDisplay().map { it.notification })
        session.store.markDisplayed(n.id)
        session.store.markAcknowledged(n.id)
        session.store.saveFetched(listOf(notification(2)))
        expectFailure<NotificationStoreCapacityException> { session.store.saveFetched(listOf(notification(3))) }
        assertEquals(2, session.store.stats().totalRecords)
        assertEquals(SaveNotificationsResult(0, 1, 0), session.store.saveFetched(listOf(n)))
        now += NORMAL_NOTIFICATION_TTL_MILLIS
        session.store.saveFetched(listOf(notification(4)))
        assertEquals(1, session.store.stats().totalRecords)
    }

    @Test
    fun byteCapacityRejectsWholeBatchWithoutPartialPersistence() = runTest {
        val session = open(policy = NotificationStoragePolicy(maxBytes = 1500))
        session.store.saveFetched(listOf(notification(1)))
        expectFailure<NotificationStoreCapacityException> {
            session.store.saveFetched(listOf(notification(2), notification(3).copy(body = "x".repeat(2000))))
        }
        assertEquals(listOf(id(1)), session.store.pendingDisplay().map { it.id })
        session.close()
        val reopened = open(session.file)
        assertEquals(listOf(id(1)), reopened.store.pendingDisplay().map { it.id })
    }

    @Test
    fun fetchPreflightReservesFullPageAndBytesAndCleanupRestoresSpace() = runTest {
        val session = open(policy = NotificationStoragePolicy(maxRecords = 100))
        assertTrue(session.store.canFetchPage())
        session.store.saveFetched(listOf(notification(1)))
        assertFalse(session.store.canFetchPage())
        now += NORMAL_NOTIFICATION_TTL_MILLIS
        assertTrue(session.store.canFetchPage())
        val small = open(policy = NotificationStoragePolicy(maxBytes = 1024 * 1024))
        assertFalse(small.store.canFetchPage())
    }

    @Test
    fun concurrentSavesDeduplicateAndAllocateDistinctStableAndroidIds() = runTest {
        val session = open()
        val results = (1..20).map {
            async { session.store.saveFetched(listOf(notification(1))) }
        }.awaitAll()
        assertEquals(1, results.sumOf { it.inserted })
        assertEquals(19, results.sumOf { it.duplicates })
        (2..20).map { index ->
            async { session.store.saveFetched(listOf(notification(index))) }
        }.awaitAll()
        val rows = session.store.pendingDisplay()
        assertEquals(20, rows.size)
        assertEquals(20, rows.map { it.androidNotificationId }.distinct().size)
    }

    @Test
    fun deviceSwitchClearsOldInboxAndStaleHandleCannotReadWriteOrAckNewDevice() = runTest {
        val first = open()
        first.store.saveFetched(listOf(notification(1)))
        val firstAndroidId = first.store.pendingDisplay().single().androidNotificationId
        val second = PersistentNotificationStore.open(first.dataStore, DEVICE_TWO, nowMillis = { now })
        assertTrue(second.pendingDisplay().isEmpty())
        second.saveFetched(listOf(notification(2)))
        assertTrue(second.pendingDisplay().single().androidNotificationId > firstAndroidId)
        expectFailure<NotificationStoreOwnerChangedException> { first.store.pendingDisplay() }
        expectFailure<NotificationStoreOwnerChangedException> { first.store.saveFetched(listOf(notification(3))) }
        expectFailure<NotificationStoreOwnerChangedException> { first.store.markDisplayed(id(2)) }
        assertEquals(listOf(id(2)), second.pendingDisplay().map { it.id })
    }

    @Test
    fun diskWriteFailureKeepsPreviousSnapshotAndPropagatesToCaller() = runTest {
        val serializer = FailingSerializer()
        val session = open(serializer = serializer)
        session.store.saveFetched(listOf(notification(1)))
        serializer.failWrites = true
        expectFailure<IOException> { session.store.saveFetched(listOf(notification(2))) }
        assertEquals(listOf(id(1)), session.store.pendingDisplay().map { it.id })
        session.close()
        val reopened = open(session.file)
        assertEquals(listOf(id(1)), reopened.store.pendingDisplay().map { it.id })
    }

    @Test
    fun corruptOrUnknownVersionFileFailsClosedRatherThanSilentlyResetting() = runTest {
        for (contents in listOf("{broken", """{"version":99}""")) {
            val file = File(temporaryFolder.root, UUID.randomUUID().toString() + ".json")
            file.writeText(contents)
            expectFailure<CorruptionException> { open(file) }
            assertEquals(contents, file.readText())
        }
    }

    @Test
    fun invalidBatchCannotPartiallySaveValidEntries() = runTest {
        val session = open()
        expectFailure<IllegalArgumentException> {
            session.store.saveFetched(listOf(notification(1), notification(2).copy(id = "bad-id")))
        }
        assertEquals(0, session.store.stats().totalRecords)
        expectFailure<IllegalArgumentException> {
            session.store.saveFetched((1..101).map { notification(it) })
        }
        assertEquals(0, session.store.stats().totalRecords)
    }

    @Test
    fun serializerRejectsDuplicateIdsAndImpossibleDeliveryState() = runTest {
        val session = open()
        session.store.saveFetched(listOf(notification(1)))
        val root = JSONObject(session.file.readText())
        val rows = root.getJSONArray("records")
        rows.put(JSONObject(rows.getJSONObject(0).toString()))
        expectFailure<CorruptionException> {
            NotificationStoreSerializer.readFrom(ByteArrayInputStream(root.toString().toByteArray()))
        }
        val impossible = JSONObject(session.file.readText())
        impossible.getJSONArray("records").getJSONObject(0).put("state", "ACKNOWLEDGED")
        expectFailure<CorruptionException> {
            NotificationStoreSerializer.readFrom(ByteArrayInputStream(impossible.toString().toByteArray()))
        }
        val invalidSeverity = JSONObject(session.file.readText())
        invalidSeverity.getJSONArray("records").getJSONObject(0)
            .getJSONObject("notification").put("severity", "unknown")
        expectFailure<CorruptionException> {
            NotificationStoreSerializer.readFrom(ByteArrayInputStream(invalidSeverity.toString().toByteArray()))
        }
    }


    @Test
    fun deliveryPipelineRestoresAckAfterRestartWithoutShowingDuplicate() = runTest {
        val first = open()
        val n = notification(1)
        val presentedIds = mutableListOf<Int>()
        val sender = object : AppNotificationSender {
            override fun canSendNotifications() = true
            override fun show(notification: AppNotification): Boolean {
                presentedIds.add(notification.id)
                return true
            }
            override fun cancel(id: Int) = Unit
        }
        var ackStatus = 503
        val ackPaths = mutableListOf<String>()
        val transport = object : com.example.accessibility_service.networking.NotificationsHttpTransport {
            override fun execute(method: String, path: String, token: String):
                com.example.accessibility_service.networking.NotificationsHttpResponse {
                assertEquals("POST", method)
                assertEquals("device-token", token)
                ackPaths.add(path)
                return com.example.accessibility_service.networking.NotificationsHttpResponse(ackStatus)
            }
        }
        val api = com.example.accessibility_service.networking.NotificationsApiClient(
            transport, kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
        )
        val manager = AppNotificationManager(sender)
        val coordinator = NotificationDeliveryCoordinator(first.store, manager, api, nowMillis = { now })
        assertEquals(NotificationDeliveryStatus.RETRYABLE_FAILURE,
            coordinator.processFetched("device-token", listOf(n)).status)
        assertEquals(1, first.store.pendingAcknowledgements().size)
        first.close()

        val reopened = open(first.file)
        ackStatus = 204
        val resumed = NotificationDeliveryCoordinator(reopened.store, manager, api, nowMillis = { now })
        assertEquals(1, resumed.resumePending("device-token").acknowledged)
        resumed.processFetched("device-token", listOf(n))
        assertEquals(1, presentedIds.size)
        assertEquals(listOf("/v1/notifications/${n.id}/ack", "/v1/notifications/${n.id}/ack"), ackPaths)
        assertEquals(1, reopened.store.stats().completed)
        assertTrue(reopened.store.pendingDisplay().isEmpty())
        assertTrue(reopened.store.pendingAcknowledgements().isEmpty())
    }

    private suspend fun open(
        file: File = File(temporaryFolder.root, UUID.randomUUID().toString() + ".json"),
        deviceId: String = DEVICE_ONE,
        policy: NotificationStoragePolicy = NotificationStoragePolicy(),
        serializer: Serializer<NotificationStoreSnapshot> = NotificationStoreSerializer,
    ): TestStore {
        val job = SupervisorJob()
        jobs.add(job)
        // Android FileStorage uses File.renameTo, which cannot replace an existing file on Windows.
        // Exercise the real DataStore/serializer on disk with its portable Okio storage backend.
        val storage = OkioStorage(
            fileSystem = FileSystem.SYSTEM,
            serializer = object : OkioSerializer<NotificationStoreSnapshot> {
                override val defaultValue get() = serializer.defaultValue
                override suspend fun readFrom(source: BufferedSource) = serializer.readFrom(source.inputStream())
                override suspend fun writeTo(t: NotificationStoreSnapshot, sink: BufferedSink) =
                    serializer.writeTo(t, sink.outputStream())
            },
            producePath = { file.absolutePath.toPath() },
        )
        val dataStore = DataStoreFactory.create(storage = storage, scope = CoroutineScope(Dispatchers.IO + job))
        val store = PersistentNotificationStore.open(dataStore, deviceId, policy, nowMillis = { now })
        return TestStore(file, job, dataStore, store)
    }

    private fun notification(index: Int, ageMillis: Long = 0) = ServerNotification(
        id = id(index),
        category = "hakaret",
        severity = NotificationSeverity.HIGH,
        title = "GiDA",
        body = "Generic notification.",
        createdAt = Instant.ofEpochMilli(now - ageMillis).toString(),
        isTest = false,
    )
}

private data class TestStore(
    val file: File,
    val job: Job,
    val dataStore: DataStore<NotificationStoreSnapshot>,
    val store: PersistentNotificationStore,
) {
    suspend fun close() = job.cancelAndJoin()
}

private class FailingSerializer : Serializer<NotificationStoreSnapshot> {
    var failWrites = false
    override val defaultValue get() = NotificationStoreSerializer.defaultValue
    override suspend fun readFrom(input: InputStream) = NotificationStoreSerializer.readFrom(input)
    override suspend fun writeTo(t: NotificationStoreSnapshot, output: OutputStream) {
        if (failWrites) {
            output.write("{partial".toByteArray())
            throw IOException("Simulated disk write failure")
        }
        NotificationStoreSerializer.writeTo(t, output)
    }
}

private suspend inline fun <reified T : Throwable> expectFailure(action: suspend () -> Unit) {
    try {
        action()
    } catch (error: Throwable) {
        if (error is T) return
        throw error
    }
    fail("Expected " + T::class.java.simpleName)
}

private fun id(index: Int) = "019a0a45-7000-7000-8000-" + index.toString().padStart(12, '0')
private const val DEVICE_ONE = "019a0a45-7000-7000-8000-100000000001"
private const val DEVICE_TWO = "019a0a45-7000-7000-8000-100000000002"
private const val HOUR = 60L * 60 * 1000
private const val DAY = 24 * HOUR
