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

class InventorySocketTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test
    fun opensOwnerScopedSocketWithSerializedHello() = runBlocking {
        val message = CompletableDeferred<String>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    message.complete(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, null)
                }
            }),
        )

        val socket = InventorySocket(
            baseUrl = server.url("/").toString().removeSuffix("/"),
            userToken = "token-\"\\\n",
            clientId = "inventory-1",
        )
        val hello = Json.parseToJsonElement(withTimeout(3_000) { message.await() }).jsonObject

        assertEquals("hello", hello["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("token-\"\\\n", hello["token"]?.jsonPrimitive?.contentOrNull)
        assertEquals("inventory-1", hello["client_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("android", hello["client_name"]?.jsonPrimitive?.contentOrNull)
        val request = withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) }
        assertEquals("/ws/inventory", request?.path)

        socket.close()
    }
}
