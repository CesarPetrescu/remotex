package app.remotex.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.remotex.model.Host
import app.remotex.model.UiEvent
import app.remotex.net.RelayClient
import app.remotex.net.SessionSocket
import app.remotex.net.SocketEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.UUID

enum class Screen { Hosts, Session }

enum class Status { Idle, Opening, Connecting, Connected, Disconnected, Error }

data class SessionInfo(
    val sessionId: String,
    val hostId: String,
    val model: String? = null,
    val cwd: String? = null,
)

data class UiState(
    val screen: Screen = Screen.Hosts,
    val userToken: String = "demo-user-token",
    val hosts: List<Host> = emptyList(),
    val selectedHostId: String? = null,
    val loading: Boolean = false,
    val status: Status = Status.Idle,
    val session: SessionInfo? = null,
    val events: List<UiEvent> = emptyList(),
    val pending: Boolean = false,
    val error: String? = null,
    val model: String = "",          // empty → codex default (gpt-5.4 at time of writing)
    val effort: String = "medium",   // none/minimal/low/medium/high/xhigh
)

/**
 * Reasoning effort levels. Empty string → don't override; let codex use
 * the model's default (almost always "medium"). The other values are
 * the intersection of what every visible model supports, so picking one
 * won't fail for the model the user chose.
 */
val REASONING_EFFORTS = listOf("", "low", "medium", "high", "xhigh")

/**
 * Pinned list of models exposed by `codex 0.122.0` (visible, non-hidden).
 * First option "" means "let codex use its configured default" — usually
 * gpt-5.4. Keep in sync with `codex app-server`'s `model/list`; you can
 * re-pull the live list anytime from a daemon with:
 *
 *     cat /tmp/list-models3.py  # see repo tooling
 */
data class ModelOption(val id: String, val label: String, val hint: String)

val MODEL_OPTIONS = listOf(
    ModelOption("", "default", "codex picks"),
    ModelOption("gpt-5.4", "gpt-5.4", "latest frontier (default)"),
    ModelOption("gpt-5.4-mini", "gpt-5.4 · mini", "smaller frontier"),
    ModelOption("gpt-5.3-codex", "gpt-5.3 · codex", "codex-optimized"),
    ModelOption("gpt-5.3-codex-spark", "gpt-5.3 · codex spark", "ultra-fast coding"),
    ModelOption("gpt-5.2", "gpt-5.2", "long-running agents"),
    ModelOption("gpt-5.2-codex", "gpt-5.2 · codex", "codex-optimized"),
    ModelOption("gpt-5.1-codex-max", "gpt-5.1 · codex max", "deep reasoning"),
    ModelOption("gpt-5.1-codex-mini", "gpt-5.1 · codex mini", "cheaper/faster"),
)

class RemotexViewModel(private val relayUrl: String) : ViewModel() {
    private val client = RelayClient(baseUrl = relayUrl)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private var socket: SessionSocket? = null
    private var socketJob: Job? = null

    fun setToken(token: String) {
        _state.update { it.copy(userToken = token) }
    }

    fun setModel(model: String) {
        _state.update { it.copy(model = model) }
    }

    fun setEffort(effort: String) {
        _state.update { it.copy(effort = effort) }
    }

    fun selectHost(id: String) {
        _state.update { it.copy(selectedHostId = id) }
    }

    fun goToHosts() {
        closeSession()
        _state.update { it.copy(screen = Screen.Hosts) }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val hosts = client.listHosts(_state.value.userToken)
                _state.update { it.copy(hosts = hosts, loading = false) }
            } catch (t: Throwable) {
                _state.update { it.copy(loading = false, error = t.message ?: "refresh failed") }
            }
        }
    }

    /** Tap on a host → pick + immediately open session for it. */
    fun openHost(host: Host) {
        if (!host.online) {
            _state.update { it.copy(error = "${host.nickname} is offline") }
            return
        }
        _state.update { it.copy(selectedHostId = host.id) }
        openSession()
    }

    fun openSession() {
        val target = _state.value.selectedHostId ?: return
        closeSession()
        _state.update {
            it.copy(
                screen = Screen.Session,
                status = Status.Opening,
                error = null,
                events = emptyList(),
                session = null,
            )
        }
        viewModelScope.launch {
            val sid = try {
                client.openSession(_state.value.userToken, target)
            } catch (t: Throwable) {
                _state.update {
                    it.copy(status = Status.Error, error = t.message ?: "open failed")
                }
                return@launch
            }
            _state.update {
                it.copy(
                    session = SessionInfo(sessionId = sid, hostId = target),
                    status = Status.Connecting,
                )
            }
            val sock = SessionSocket(relayUrl, _state.value.userToken, sid)
            socket = sock
            socketJob = launch {
                sock.events.collect { ev ->
                    when (ev) {
                        is SocketEvent.Frame -> handleFrame(ev.text)
                        is SocketEvent.Closed -> _state.update {
                            it.copy(status = Status.Disconnected, pending = false)
                        }
                        is SocketEvent.Failure -> _state.update {
                            it.copy(
                                status = Status.Error,
                                error = ev.throwable.message ?: "socket error",
                                pending = false,
                            )
                        }
                    }
                }
            }
        }
    }

    fun sendTurn(text: String) {
        val input = text.trim()
        if (input.isEmpty()) return
        val sock = socket ?: return
        val userId = "u-${UUID.randomUUID().toString().take(8)}"
        _state.update {
            it.copy(
                events = it.events + UiEvent.User(id = userId, text = input),
                pending = true,
            )
        }
        val model = _state.value.model.trim()
        val effort = _state.value.effort.trim()
        val frame = Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("type", "turn-start")
                put("input", input)
                if (model.isNotEmpty()) put("model", model)
                if (effort.isNotEmpty() && effort != "none") put("effort", effort)
            },
        )
        sock.sendJson(frame)
    }

    fun closeSession() {
        socket?.close()
        socket = null
        socketJob?.cancel()
        socketJob = null
        _state.update { it.copy(status = Status.Idle, pending = false) }
    }

    // --- frame parsing ------------------------------------------------------

    private fun handleFrame(raw: String) {
        val msg = try {
            json.parseToJsonElement(raw).jsonObject
        } catch (_: Throwable) {
            return
        }
        when (msg.string("type")) {
            "attached" -> _state.update { it.copy(status = Status.Connected) }
            "session-closed" -> _state.update {
                it.copy(status = Status.Disconnected, pending = false)
            }
            "session-event" -> {
                val ev = msg["event"]?.jsonObject ?: return
                val kind = ev.string("kind") ?: return
                val data = ev["data"]?.jsonObject ?: JsonObject(emptyMap())
                applyEvent(kind, data)
            }
            "error" -> _state.update {
                it.copy(error = msg.string("error") ?: "relay error")
            }
        }
    }

    private fun applyEvent(kind: String, data: JsonObject) {
        when (kind) {
            "session-started" -> _state.update {
                it.copy(
                    status = Status.Connected,
                    session = it.session?.copy(
                        model = data.string("model") ?: it.session.model,
                        cwd = data.string("cwd") ?: it.session.cwd,
                    ),
                )
            }

            "turn-started" -> {
                // Our own User bubble was added client-side in sendTurn, nothing to add here.
            }

            "item-started" -> {
                val itemId = data.string("item_id") ?: return
                val itemType = data.string("item_type") ?: return
                val next = when (itemType) {
                    "agent_reasoning" -> UiEvent.Reasoning(id = itemId, text = "", completed = false)
                    "agent_message" -> UiEvent.Agent(id = itemId, text = "", completed = false)
                    "tool_call" -> UiEvent.Tool(
                        id = itemId,
                        tool = data.string("tool") ?: "tool",
                        command = data.obj("args")?.string("command") ?: "",
                        output = "",
                        completed = false,
                    )
                    else -> UiEvent.System(id = itemId, label = itemType, detail = "")
                }
                _state.update { it.copy(events = it.events + next) }
            }

            "item-delta" -> {
                val itemId = data.string("item_id") ?: return
                val delta = data.string("delta") ?: ""
                if (delta.isEmpty()) return
                _state.update { s ->
                    s.copy(events = s.events.map { e ->
                        if (e.id != itemId) e else when (e) {
                            is UiEvent.Agent -> e.copy(text = e.text + delta)
                            is UiEvent.Reasoning -> e.copy(text = e.text + delta)
                            is UiEvent.Tool -> e.copy(output = e.output + delta)
                            else -> e
                        }
                    })
                }
            }

            "item-completed" -> {
                val itemId = data.string("item_id") ?: return
                _state.update { s ->
                    s.copy(events = s.events.map { e ->
                        if (e.id != itemId) e else when (e) {
                            is UiEvent.Agent -> e.copy(
                                text = data.string("text") ?: e.text,
                                completed = true,
                            )
                            is UiEvent.Reasoning -> e.copy(
                                text = data.string("text") ?: e.text,
                                completed = true,
                            )
                            is UiEvent.Tool -> e.copy(
                                output = data.string("output") ?: e.output,
                                completed = true,
                            )
                            else -> e
                        }
                    })
                }
            }

            "turn-completed" -> _state.update { it.copy(pending = false) }

            "thread-status" -> {
                // low-signal noise for now; skip
            }
        }
    }

    override fun onCleared() {
        closeSession()
        super.onCleared()
    }

    companion object {
        fun factory(relayUrl: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    RemotexViewModel(relayUrl) as T
            }
    }
}

// Tiny helpers so the dispatch code above doesn't drown in ?.jsonObject?.get(...) chains.
private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.obj(key: String): JsonObject? =
    (this[key] as? JsonElement) as? JsonObject
