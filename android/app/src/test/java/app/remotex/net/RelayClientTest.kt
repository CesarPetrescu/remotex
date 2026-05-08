package app.remotex.net

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class RelayClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: RelayClient

    @Before
    fun setUp() {
        server = MockWebServer()
        client = RelayClient(server.url("/").toString().removeSuffix("/"))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun listModelsParsesRelayResponseWithoutAuthHeader() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "models": [
                        {
                          "id": "gpt-5.4-mini",
                          "label": "gpt-5.4 mini",
                          "hint": "fast",
                          "efforts": ["", "low", "medium"]
                        }
                      ],
                      "efforts": ["", "low", "medium"],
                      "ignored": true
                    }
                    """.trimIndent(),
                ),
        )

        val models = client.listModels()

        assertEquals(1, models.size)
        assertEquals("gpt-5.4-mini", models.single().id)
        assertEquals(listOf("", "low", "medium"), models.single().efforts)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/models", request.path)
        assertNull(request.getHeader("Authorization"))
    }

    @Test
    fun openSessionSendsCodexSessionPayload() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"session_id":"sess_123"}"""),
        )

        val sessionId = client.openSession(
            userToken = "user-token",
            hostId = "host_1",
            resumeThreadId = "thread_1",
            cwd = "/tmp/remotex",
        )

        assertEquals("sess_123", sessionId)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/sessions", request.path)
        assertEquals("Bearer user-token", request.getHeader("Authorization"))

        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("host_1", body["host_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("thread_1", body["thread_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("/tmp/remotex", body["cwd"]?.jsonPrimitive?.contentOrNull)
        assertNull(body["kind"])
        assertNull(body["task"])
        assertNull(body["model"])
        assertNull(body["effort"])
        assertNull(body["permissions"])
        assertNull(body["approval_policy"])
    }

    @Test
    fun readDirectoryEncodesPathAndParsesEntries() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "host_id": "host_1",
                      "path": "/tmp/remotex space",
                      "entries": [
                        {"fileName": "src", "isDirectory": true},
                        {"fileName": "README.md", "isFile": true}
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val result = client.readDirectory("user-token", "host_1", "/tmp/remotex space")

        assertEquals("host_1", result.hostId)
        assertEquals("/tmp/remotex space", result.path)
        assertEquals(2, result.entries.size)
        assertEquals("src", result.entries[0].fileName)
        assertEquals(true, result.entries[0].isDirectory)
        assertEquals("README.md", result.entries[1].fileName)
        assertEquals(true, result.entries[1].isFile)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/hosts/host_1/fs?path=%2Ftmp%2Fremotex+space", request.path)
        assertEquals("Bearer user-token", request.getHeader("Authorization"))
    }
}
