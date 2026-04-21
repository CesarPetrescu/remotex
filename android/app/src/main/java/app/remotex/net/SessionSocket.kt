package app.remotex.net

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

sealed interface SocketEvent {
    data class Frame(val text: String) : SocketEvent
    data class Closed(val reason: String) : SocketEvent
    data class Failure(val throwable: Throwable) : SocketEvent
}

// Opens /ws/client, sends the hello envelope, then emits raw JSON frames
// as [SocketEvent.Frame]. Caller parses — the socket layer stays dumb on
// purpose so approvals etc. can land without this file changing.
fun openSessionSocket(
    baseUrl: String,
    userToken: String,
    sessionId: String,
    http: OkHttpClient = OkHttpClient(),
): Flow<SocketEvent> = callbackFlow {
    val wsUrl = baseUrl
        .replaceFirst("https://", "wss://")
        .replaceFirst("http://", "ws://") + "/ws/client"
    val req = Request.Builder().url(wsUrl).build()

    val socket: WebSocket = http.newWebSocket(req, object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(
                """{"type":"hello","token":"$userToken","session_id":"$sessionId"}"""
            )
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            trySend(SocketEvent.Frame(text))
        }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            trySend(SocketEvent.Frame(bytes.utf8()))
        }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            trySend(SocketEvent.Closed(reason))
            close()
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            trySend(SocketEvent.Failure(t))
            close(t)
        }
    })

    awaitClose { socket.close(1000, "cancelled") }
}
