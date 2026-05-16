package com.remotecamera.app

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ClaudeInspectorTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun inspectPostsAnthropicMessageWithImageAndParsesFencedJson() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "content": [
                        {
                          "type": "text",
                          "text": "```json\n{\"status\":\"DANGER\",\"severity\":\"HIGH\",\"summary\":\"worker in danger zone\",\"evidence\":[\"person near equipment\"],\"recommendations\":[\"check immediately\"],\"falsePositiveLikely\":false}\n```"
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )
        val inspector = ClaudeInspector(
            client = OkHttpClient(),
            model = "claude-test",
            apiUrl = server.url("/v1/messages").toString(),
        )

        val result = inspector.inspect(
            jpegBytes = byteArrayOf(1, 2, 3),
            apiKey = "test-key",
            context = ClaudeInspectionContext(
                source = "manual",
                yoloStatus = "NG",
                detectionCount = 1,
                areaConfigured = true,
            ),
        )

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("test-key", request.getHeader("x-api-key"))
        assertEquals("2023-06-01", request.getHeader("anthropic-version"))
        val payload = JSONObject(request.body.readUtf8())
        assertEquals("claude-test", payload.getString("model"))
        val content = payload.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals("image", content.getJSONObject(0).getString("type"))
        assertEquals("AQID", content.getJSONObject(0).getJSONObject("source").getString("data"))
        assertEquals("text", content.getJSONObject(1).getString("type"))

        assertEquals("DANGER", result.status)
        assertEquals("HIGH", result.severity)
        assertEquals("worker in danger zone", result.summary)
        assertEquals(listOf("person near equipment"), result.evidence)
        assertEquals(listOf("check immediately"), result.recommendations)
        assertFalse(result.falsePositiveLikely)
    }

    @Test
    fun inspectParsesJsonEmbeddedInClaudeText() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "content": [
                        {
                          "type": "text",
                          "text": "Analysis result:\n{\"status\":\"SAFE\",\"severity\":\"NONE\",\"summary\":\"looks like false positive\",\"evidence\":[],\"recommendations\":[],\"false_positive_likely\":true}\nPlease confirm."
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )
        val inspector = ClaudeInspector(
            client = OkHttpClient(),
            model = "claude-test",
            apiUrl = server.url("/v1/messages").toString(),
        )

        val result = inspector.inspect(
            jpegBytes = byteArrayOf(9),
            apiKey = "test-key",
            context = ClaudeInspectionContext(
                source = "stream",
                yoloStatus = "NG",
                detectionCount = 1,
                areaConfigured = false,
            ),
        )

        assertEquals("SAFE", result.status)
        assertEquals("NONE", result.severity)
        assertEquals("looks like false positive", result.summary)
        assertTrue(result.falsePositiveLikely)
    }

    @Test
    fun inspectUsesProvidedImageMimeType() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "content": [
                        {
                          "type": "text",
                          "text": "{\"status\":\"SAFE\",\"severity\":\"NONE\",\"summary\":\"ok\",\"evidence\":[],\"recommendations\":[],\"falsePositiveLikely\":false}"
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )
        val inspector = ClaudeInspector(
            client = OkHttpClient(),
            model = "claude-test",
            apiUrl = server.url("/v1/messages").toString(),
        )

        inspector.inspect(
            jpegBytes = byteArrayOf(1),
            imageMimeType = "image/png",
            apiKey = "test-key",
            context = ClaudeInspectionContext(
                source = "image",
                yoloStatus = "NG",
                detectionCount = 1,
                areaConfigured = false,
            ),
        )

        val payload = JSONObject(server.takeRequest().body.readUtf8())
        val source = payload
            .getJSONArray("messages")
            .getJSONObject(0)
            .getJSONArray("content")
            .getJSONObject(0)
            .getJSONObject("source")
        assertEquals("image/png", source.getString("media_type"))
    }
}
