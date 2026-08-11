package app.remotex.net

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

sealed interface SocketEvent {
    data class Frame(val text: String) : SocketEvent
    data class Closed(val reason: String) : SocketEvent
    data class Failure(val throwable: Throwable) : SocketEvent
}

internal fun buildHelloFrame(
    userToken: String,
    sessionId: String,
    clientId: String,
    clientName: String,
    lastSeq: Long,
): String = Json.encodeToString(
    JsonObject.serializer(),
    buildJsonObject {
        put("type", "hello")
        put("token", userToken)
        put("session_id", sessionId)
        put("client_id", clientId)
        put("client_name", clientName)
        put("last_seq", lastSeq)
    },
)

/**
 * Thin wrapper over OkHttp's WebSocket. Exposes incoming traffic as a
 * [Flow] and lets callers push frames back through [sendJson].
 * Caller owns the lifecycle via [close].
 */
class SessionSocket(
    baseUrl: String,
    userToken: String,
    sessionId: String,
    clientId: String,
    lastSeq: Long = 0L,
    clientName: String = "android",
    allowInsecureHttp: Boolean = true,
    http: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build(),
) {
    // Unbounded mailbox, not a SharedFlow: the socket is opened in `init`,
    // so the relay's opening burst (attached → pending-prompts → replay →
    // session-started) lands before the collector is running, and every one
    // of those frames is load-bearing. A Channel buffers from open onward
    // and hands the backlog to the single collector in arrival order —
    // nothing is dropped and nothing is reordered. `receiveAsFlow` is
    // single-consumer, which matches the one-collector-per-socket lifecycle
    // in RemotexViewModel.attachSocket.
    private val _events = Channel<SocketEvent>(Channel.UNLIMITED)
    val events: Flow<SocketEvent> = _events.receiveAsFlow()

    private val socket: WebSocket

    init {
        val wsUrl = requireRelayBaseUrl(baseUrl, allowInsecureHttp)
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://") + "/ws/client"
        val req = Request.Builder().url(wsUrl).build()
        socket = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(buildHelloFrame(userToken, sessionId, clientId, clientName, lastSeq))
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                _events.trySend(SocketEvent.Frame(text))
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                _events.trySend(SocketEvent.Frame(bytes.utf8()))
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // Answer the peer's close frame. OkHttp does not complete the
                // handshake for us, and a half-closed socket keeps the TCP
                // connection — and the relay's peer slot — alive until it
                // times out. The relay closes sockets on its own initiative
                // (4401 on a revoked key, 1013 for a slow consumer), so this
                // is the common path, not an edge case.
                webSocket.close(code, null)
                finish(SocketEvent.Closed(reason))
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                finish(SocketEvent.Failure(t))
            }
        })
    }

    // Terminal event, then close the mailbox so the collector completes
    // once it has drained whatever was still buffered behind it. Closing
    // for send keeps already-queued frames deliverable.
    private fun finish(event: SocketEvent) {
        _events.trySend(event)
        _events.close()
    }

    fun sendJson(json: String): Boolean {
        return socket.send(json)
    }

    fun close(endSession: Boolean = false) {
        if (endSession) {
            socket.send("""{"type":"session-close"}""")
        }
        socket.close(1000, "client-closed")
    }
}
