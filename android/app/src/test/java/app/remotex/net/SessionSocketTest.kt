package app.remotex.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Server-side listener that answers the client's close frame.
 *
 * `MockWebServer.shutdown()` waits for its dispatcher queue to drain, and a
 * websocket whose close handshake is still half-finished keeps that queue
 * busy — teardown then fails with "Gave up waiting for queue to shut down",
 * intermittently and in whichever test happened to run last. Completing the
 * handshake from the server side makes shutdown deterministic.
 */
private abstract class ClosingWebSocketListener : WebSocketListener() {
    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(code, null)
    }
}

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
            MockResponse().withWebSocketUpgrade(object : ClosingWebSocketListener() {
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

    /**
     * The socket is opened in `init`, so the relay's opening burst lands
     * before the ViewModel starts collecting. Every one of those frames is
     * load-bearing, so none of them may be dropped — the old replay-0
     * SharedFlow with a 64-frame DROP_OLDEST buffer lost them.
     */
    @Test
    fun deliversEveryFrameFromOpenEvenWhenCollectionStartsLate() = runBlocking {
        val burst = 200
        val sent = CompletableDeferred<Unit>()

        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : ClosingWebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    // text is the client's hello; answer with a burst.
                    repeat(burst) { i -> webSocket.send("""{"type":"session-event","seq":$i}""") }
                    sent.complete(Unit)
                }
            }),
        )

        val socket = SessionSocket(
            baseUrl = server.url("/").toString().removeSuffix("/"),
            userToken = "user-token",
            sessionId = "sess_1",
            clientId = "android_1",
        )

        withTimeout(5_000) { sent.await() }
        // Nothing is collecting yet, and the delay gives the reader thread
        // time to hand every frame to the socket's mailbox.
        delay(500)

        val frames = withTimeout(5_000) {
            socket.events.filterIsInstance<SocketEvent.Frame>().take(burst).toList()
        }

        assertEquals(burst, frames.size)
        frames.forEachIndexed { i, frame ->
            val seq = Json.parseToJsonElement(frame.text)
                .jsonObject["seq"]?.jsonPrimitive?.longOrNull
            assertEquals(i.toLong(), seq)
        }

        socket.close()
    }

    /** A terminal event never jumps the queue: buffered frames are delivered
     *  first, then Closed, and only then does the flow complete. */
    @Test
    fun closeArrivesAfterBufferedFramesAndCompletesTheFlow() = runBlocking {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : ClosingWebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    webSocket.send("""{"type":"attached"}""")
                    webSocket.send("""{"type":"pending-prompts"}""")
                    webSocket.close(1000, "bye")
                }
            }),
        )

        val socket = SessionSocket(
            baseUrl = server.url("/").toString().removeSuffix("/"),
            userToken = "user-token",
            sessionId = "sess_1",
            clientId = "android_1",
        )

        val events = withTimeout(5_000) { socket.events.toList() }

        assertEquals(3, events.size)
        assertEquals("""{"type":"attached"}""", (events[0] as SocketEvent.Frame).text)
        assertEquals("""{"type":"pending-prompts"}""", (events[1] as SocketEvent.Frame).text)
        assertTrue(events[2] is SocketEvent.Closed)

        socket.close()
    }
}
