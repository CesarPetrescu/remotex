package app.remotex.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class SessionSocketTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun opensClientWebSocketAndSendsHelloFrame() = runBlocking {
        val firstMessage = CompletableDeferred<String>()
        val secondMessage = CompletableDeferred<String>()

        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (!firstMessage.complete(text)) {
                        secondMessage.complete(text)
                    }
                }
            }),
        )

        val socket = SessionSocket(
            baseUrl = server.url("/").toString().removeSuffix("/"),
            userToken = "user-token",
            sessionId = "sess_1",
            clientId = "android_1",
            lastSeq = 42L,
            clientName = "android-test",
        )

        val hello = Json.parseToJsonElement(
            withTimeout(3_000) { firstMessage.await() },
        ).jsonObject
        assertEquals("hello", hello["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("user-token", hello["token"]?.jsonPrimitive?.contentOrNull)
        assertEquals("sess_1", hello["session_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("android_1", hello["client_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("android-test", hello["client_name"]?.jsonPrimitive?.contentOrNull)
        assertEquals(42L, hello["last_seq"]?.jsonPrimitive?.longOrNull)

        val request = withContext(Dispatchers.IO) {
            server.takeRequest(3, TimeUnit.SECONDS)
        }
        assertEquals("/ws/client", request?.path)

        socket.sendJson("""{"type":"ping","ts":123}""")
        val ping = Json.parseToJsonElement(
            withTimeout(3_000) { secondMessage.await() },
        ).jsonObject
        assertEquals("ping", ping["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals(123L, ping["ts"]?.jsonPrimitive?.longOrNull)

        socket.close()
    }
}
