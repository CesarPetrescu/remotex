package app.remotex.net

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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

/**
 * Thin wrapper over OkHttp's WebSocket. Exposes incoming traffic as a
 * [SharedFlow] and lets callers push frames back through [sendJson].
 * Caller owns the lifecycle via [close].
 */
class SessionSocket(
    baseUrl: String,
    userToken: String,
    sessionId: String,
    clientId: String,
    lastSeq: Long = 0L,
    clientName: String = "android",
    http: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build(),
) {
    private val _events = MutableSharedFlow<SocketEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<SocketEvent> = _events.asSharedFlow()

    private val socket: WebSocket

    init {
        val wsUrl = baseUrl
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://") + "/ws/client"
        val req = Request.Builder().url(wsUrl).build()
        socket = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(
                    """{"type":"hello","token":"$userToken","session_id":"$sessionId","client_id":"$clientId","client_name":"$clientName","last_seq":$lastSeq}"""
                )
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                _events.tryEmit(SocketEvent.Frame(text))
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                _events.tryEmit(SocketEvent.Frame(bytes.utf8()))
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                _events.tryEmit(SocketEvent.Closed(reason))
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _events.tryEmit(SocketEvent.Failure(t))
            }
        })
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
