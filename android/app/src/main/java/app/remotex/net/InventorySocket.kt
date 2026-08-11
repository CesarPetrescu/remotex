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

internal fun buildInventoryHelloFrame(
    userToken: String,
    clientId: String,
): String = Json.encodeToString(
    JsonObject.serializer(),
    buildJsonObject {
        put("type", "hello")
        put("token", userToken)
        put("client_id", clientId)
        put("client_name", "android")
    },
)

/** One authenticated owner-scoped inventory invalidation connection. */
class InventorySocket(
    baseUrl: String,
    userToken: String,
    clientId: String,
    allowInsecureHttp: Boolean = true,
    http: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build(),
) {
    private val mailbox = Channel<SocketEvent>(Channel.UNLIMITED)
    val events: Flow<SocketEvent> = mailbox.receiveAsFlow()
    private val socket: WebSocket

    init {
        val wsUrl = requireRelayBaseUrl(baseUrl, allowInsecureHttp)
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://") + "/ws/inventory"
        socket = http.newWebSocket(
            Request.Builder().url(wsUrl).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(buildInventoryHelloFrame(userToken, clientId))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    mailbox.trySend(SocketEvent.Frame(text))
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    mailbox.trySend(SocketEvent.Frame(bytes.utf8()))
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, null)
                    finish(SocketEvent.Closed(reason))
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    finish(SocketEvent.Failure(t))
                }
            },
        )
    }

    private fun finish(event: SocketEvent) {
        mailbox.trySend(event)
        mailbox.close()
    }

    fun close() {
        socket.close(1000, "client-closed")
    }
}
