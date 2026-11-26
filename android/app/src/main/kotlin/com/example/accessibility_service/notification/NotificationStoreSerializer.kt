package com.example.accessibility_service.notification

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.example.accessibility_service.networking.NotificationSeverity
import com.example.accessibility_service.networking.ServerNotification
import com.example.accessibility_service.networking.isCanonicalNotificationId
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import org.json.JSONArray
import org.json.JSONObject

/** No corruption-reset handler: losing deduplication/ack state must not look like an empty inbox. */
internal object NotificationStoreSerializer : Serializer<NotificationStoreSnapshot> {
    override val defaultValue = NotificationStoreSnapshot()

    override suspend fun readFrom(input: InputStream): NotificationStoreSnapshot {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            if (bytes.size().toLong() + count > MAX_NOTIFICATION_STORE_BYTES) {
                throw CorruptionException("Notification store exceeds size limit")
            }
            bytes.write(buffer, 0, count)
        }
        return try {
            decode(bytes.toString(Charsets.UTF_8.name()))
        } catch (error: IllegalArgumentException) {
            throw CorruptionException("Invalid notification store", error)
        } catch (error: org.json.JSONException) {
            throw CorruptionException("Invalid notification store", error)
        } catch (error: java.time.DateTimeException) {
            throw CorruptionException("Invalid notification store", error)
        }
    }

    override suspend fun writeTo(t: NotificationStoreSnapshot, output: OutputStream) {
        val bytes = encode(t)
        if (bytes.size > MAX_NOTIFICATION_STORE_BYTES) throw NotificationStoreCapacityException()
        output.write(bytes)
    }

    internal fun encode(snapshot: NotificationStoreSnapshot): ByteArray = JSONObject()
        .put("version", 1)
        .put("device_id", snapshot.deviceId ?: JSONObject.NULL)
        .put("next_android_id", snapshot.nextAndroidId)
        .put("records", JSONArray(snapshot.records.map { row ->
            JSONObject()
                .put("id", row.id)
                .put("android_id", row.androidNotificationId)
                .put("expires_at_ms", row.expiresAtMillis)
                .put("state", row.state.name)
                .put("notification", row.notification?.let { n ->
                    JSONObject()
                        .put("category", n.category)
                        .put("severity", n.severity.wireValue)
                        .put("title", n.title)
                        .put("body", n.body)
                        .put("created_at", n.createdAt)
                        .put("is_test", n.isTest)
                } ?: JSONObject.NULL)
        }))
        .toString().toByteArray(Charsets.UTF_8)

    private fun decode(body: String): NotificationStoreSnapshot {
        val root = JSONObject(body)
        require(root.strictLong("version") == 1L)
        val owner = root.get("device_id").let { if (it == JSONObject.NULL) null else it as? String }
        require(owner != null && isCanonicalNotificationId(owner))
        val nextId = root.strictLong("next_android_id")
        require(nextId in FIRST_INBOX_ANDROID_ID.toLong()..(Int.MAX_VALUE.toLong() + 1))
        val array = root.getJSONArray("records")
        require(array.length() <= 10_000)
        val rows = List(array.length()) { index ->
            val row = array.getJSONObject(index)
            val id = row.strictString("id").also { require(isCanonicalNotificationId(it)) }
            val androidId = row.strictLong("android_id")
            require(androidId in FIRST_INBOX_ANDROID_ID.toLong() until nextId)
            val state = NotificationDeliveryState.valueOf(row.strictString("state"))
            val notificationValue = row.get("notification")
            val notification = if (notificationValue == JSONObject.NULL) null else {
                val n = notificationValue as? JSONObject ?: throw IllegalArgumentException()
                val test = n.get("is_test")
                require(test is Boolean)
                ServerNotification(
                    id = id,
                    category = n.strictString("category"),
                    severity = requireNotNull(NotificationSeverity.entries.find { it.wireValue == n.strictString("severity") }),
                    title = n.strictString("title"),
                    body = n.strictString("body"),
                    createdAt = n.strictString("created_at"),
                    isTest = test,
                ).also(::validateNotification)
            }
            require(isTerminal(state) == (notification == null))
            StoredNotification(id, androidId.toInt(), row.strictLong("expires_at_ms"), state, notification)
        }
        require(rows.map { it.id }.distinct().size == rows.size)
        require(rows.map { it.androidNotificationId }.distinct().size == rows.size)
        return NotificationStoreSnapshot(owner, nextId, rows)
    }

    private fun JSONObject.strictString(key: String): String {
        val value = get(key)
        require(value is String && value.isNotBlank())
        return value
    }

    private fun JSONObject.strictLong(key: String): Long {
        val value = get(key)
        require(value is Int || value is Long)
        return (value as Number).toLong()
    }
}
