package com.example.accessibility_service.upload

import com.example.accessibility_service.Util.BoundsInScreen
import com.example.accessibility_service.Util.CaptureNode
import com.example.accessibility_service.Util.ScreenSummary
import com.example.accessibility_service.networking.CapturesApiClient
import com.example.accessibility_service.networking.CapturesHttpResponse
import com.example.accessibility_service.networking.CapturesHttpTransport
import com.example.accessibility_service.persistence.PersistentEventQueue
import com.example.accessibility_service.persistence.QueueBatchToken
import com.example.accessibility_service.persistence.QueuedCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StructuralCapturePipelineTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun mapperDiskReopenUploadAndJsonPreserveStructuralMetadata() = runTest {
        val rootBounds = BoundsInScreen(0, 0, 1080, 1920)
        val outsideBounds = BoundsInScreen(-100, -500, 1080, 2500)
        val root = CaptureNode(className = "root", visibleToUser = true,
            boundsInScreen = rootBounds, parentIndex = null)
        val summary = ScreenSummary(
            nodeCount = 3,
            texts = listOf("informative text"),
            nodes = listOf(
                root,
                root.copy(className = "container", visibleToUser = false,
                    boundsInScreen = outsideBounds, parentIndex = 0),
                root.copy(className = "caption", text = "caption", parentIndex = 1),
            ),
            rootPackageName = "root.package",
            windowBoundsInScreen = outsideBounds,
        )
        val captures = listOf(summary, summary.copy(rootPackageName = null, windowBoundsInScreen = null))
            .map { source ->
                val capture = AccessibilityCaptureMapper.map(
                    packageName = "event.package", appName = "Example", eventType = "TYPE_VIEW_CLICKED",
                    screenSummary = source, isTargetApp = true, isSupportedEventType = false,
                )
                assertEquals("event.package", capture.packageName)
                assertEquals(source.texts, capture.screenText)
                assertEquals(source.rootPackageName, capture.rootPackageName)
                assertEquals(source.windowBoundsInScreen, capture.windowBoundsInScreen)
                assertEquals(3, capture.nodes.size)
                source.nodes.zip(capture.nodes).forEach { (expected, actual) ->
                    assertEquals(expected.text, actual.text)
                    assertEquals(expected.contentDescription, actual.contentDescription)
                    assertEquals(expected.className, actual.className)
                    assertEquals(expected.visibleToUser, actual.visibleToUser)
                    assertEquals(expected.boundsInScreen, actual.boundsInScreen)
                    assertEquals(expected.parentIndex, actual.parentIndex)
                }
                capture
            }

        val file = temporaryFolder.newFile("captures.ring")
        val initialQueue = PersistentEventQueue.openFile(file, PersistentEventQueue.DEFAULT_DATA_CAPACITY_BYTES)
        try {
            captures.forEach { initialQueue.enqueue(it) }
        } finally {
            initialQueue.close()
        }
        val queue = PersistentEventQueue.openFile(file, PersistentEventQueue.DEFAULT_DATA_CAPACITY_BYTES)
        try {
            assertEquals(captures, queue.peekBatch(10).captures)
            var requestBody: String? = null
            val api = CapturesApiClient(object : CapturesHttpTransport {
                override fun post(token: String, utf8JsonBody: String): CapturesHttpResponse {
                    assertEquals("test-token", token)
                    requestBody = utf8JsonBody
                    return CapturesHttpResponse(200,
                        """{"accepted":2,"skipped":0,"config":{"batch_size":30,"flush_seconds":20},"commands":[]}""")
                }
            }, Dispatchers.Unconfined)
            val coordinator = UploadCoordinator(
                tokenStore = object : UploadTokenStore {
                    override suspend fun getToken() = "test-token"
                    override suspend fun revokeToken() = error("Unexpected token revocation")
                },
                queue = object : UploadEventQueue {
                    override suspend fun peekBatch(maxCount: Int) = queue.peekBatch(maxCount)
                    override suspend fun acknowledge(token: QueueBatchToken) = queue.acknowledge(token)
                },
                apiClient = object : UploadCapturesClient {
                    override suspend fun sendCaptures(token: String, captures: List<QueuedCapture>) =
                        api.sendCaptures(token, captures)
                },
            )
            assertTrue(coordinator.requestUpload() is UploadOutcome.Uploaded)
            assertTrue(queue.isEmpty())
            val events = JSONObject(requireNotNull(requestBody)).getJSONArray("events")
            assertEquals(2, events.length())
            val event = events.getJSONObject(0)
            assertEquals("event.package", event.getString("packageName"))
            assertEquals("root.package", event.getString("rootPackageName"))
            assertEquals(-500, event.getJSONObject("windowBoundsInScreen").getInt("top"))
            assertEquals("informative text", event.getJSONArray("screenText").getString(0))
            val nodes = event.getJSONArray("nodes")
            assertEquals(3, nodes.length())
            assertSame(JSONObject.NULL, nodes.getJSONObject(0).get("parentIndex"))
            assertEquals(0, nodes.getJSONObject(1).getInt("parentIndex"))
            assertEquals(1, nodes.getJSONObject(2).getInt("parentIndex"))
            assertSame(JSONObject.NULL, nodes.getJSONObject(1).get("text"))
            assertSame(JSONObject.NULL, nodes.getJSONObject(1).get("contentDescription"))
            assertFalse(nodes.getJSONObject(1).getBoolean("visibleToUser"))
            assertEquals(-100, nodes.getJSONObject(1).getJSONObject("boundsInScreen").getInt("left"))
            assertEquals("caption", nodes.getJSONObject(2).getString("text"))
            assertSame(JSONObject.NULL, events.getJSONObject(1).get("rootPackageName"))
            assertSame(JSONObject.NULL, events.getJSONObject(1).get("windowBoundsInScreen"))
        } finally {
            queue.close()
        }
    }
}
