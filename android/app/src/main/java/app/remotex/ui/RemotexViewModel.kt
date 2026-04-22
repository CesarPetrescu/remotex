package app.remotex.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.remotex.model.Host
import app.remotex.model.ThreadInfo
import app.remotex.model.UiEvent
import app.remotex.net.RelayClient
import app.remotex.net.SessionSocket
import app.remotex.net.SocketEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.UUID

enum class Screen { Hosts, Threads, Session }

enum class Status { Idle, Opening, Connecting, Connected, Disconnected, Error }

data class SessionInfo(
    val sessionId: String,
    val hostId: String,
    val model: String? = null,
    val cwd: String? = null,
)

data class PendingImage(
    val uri: String,      // content:// URI for thumbnail
    val mime: String,
    val base64: String,   // encoded payload for the wire
    val label: String,    // short filename for display
)

data class ApprovalPrompt(
    val approvalId: String,
    val kind: String,          // "command" or "file_change"
    val reason: String?,
    val command: String?,
    val cwd: String?,
    val decisions: List<String>,
)

enum class PermissionsMode(val wire: String, val label: String, val hint: String) {
    Default("default", "Default", "ask for internet + outside writes"),
    Full("full", "Full Access", "no prompts — use with caution"),
    ReadOnly("readonly", "Read Only", "codex can look but not touch"),
}

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
    val threads: List<ThreadInfo> = emptyList(),
    val threadsLoading: Boolean = false,
    val pendingImages: List<PendingImage> = emptyList(),
    val permissions: PermissionsMode = PermissionsMode.Default,
    val pendingApproval: ApprovalPrompt? = null,
    val slashFeedback: String? = null,
    val planMode: Boolean = false,   // true after /plan, cleared on /default
)

/**
 * Reasoning effort levels surfaced to the UI. Empty string = "don't
 * override" (codex falls back to the model's default, usually medium).
 * Per-model support filters this set — see [ModelOption.efforts].
 */
const val EFFORT_DEFAULT = ""
val ALL_EFFORTS = listOf(EFFORT_DEFAULT, "low", "medium", "high", "xhigh")

/**
 * Visible models from `codex 0.122.0` model/list, with the exact
 * supported reasoning efforts per model so the effort picker can
 * filter itself. Keep in sync with codex upgrades.
 */
data class ModelOption(
    val id: String,
    val label: String,
    val hint: String,
    val efforts: List<String>,
)

val MODEL_OPTIONS = listOf(
    ModelOption("", "default", "codex picks", ALL_EFFORTS),
    ModelOption("gpt-5.4", "gpt-5.4", "latest frontier (default)",
        listOf(EFFORT_DEFAULT, "low", "medium", "high", "xhigh")),
    ModelOption("gpt-5.4-mini", "gpt-5.4 · mini", "smaller frontier",
        listOf(EFFORT_DEFAULT, "low", "medium", "high", "xhigh")),
    ModelOption("gpt-5.3-codex", "gpt-5.3 · codex", "codex-optimized",
        listOf(EFFORT_DEFAULT, "low", "medium", "high", "xhigh")),
    ModelOption("gpt-5.3-codex-spark", "gpt-5.3 · codex spark", "ultra-fast coding",
        listOf(EFFORT_DEFAULT, "low", "medium", "high", "xhigh")),
    ModelOption("gpt-5.2", "gpt-5.2", "long-running agents",
        listOf(EFFORT_DEFAULT, "low", "medium", "high", "xhigh")),
    ModelOption("gpt-5.2-codex", "gpt-5.2 · codex", "codex-optimized",
        listOf(EFFORT_DEFAULT, "low", "medium", "high", "xhigh")),
    ModelOption("gpt-5.1-codex-max", "gpt-5.1 · codex max", "deep reasoning",
        listOf(EFFORT_DEFAULT, "low", "medium", "high", "xhigh")),
    ModelOption("gpt-5.1-codex-mini", "gpt-5.1 · codex mini", "cheaper/faster",
        listOf(EFFORT_DEFAULT, "medium", "high")),  // only medium/high supported
)

/** Effort list the UI should show given the currently-picked model. */
fun effortsFor(modelId: String): List<String> =
    MODEL_OPTIONS.firstOrNull { it.id == modelId }?.efforts ?: ALL_EFFORTS

class RemotexViewModel(
    application: Application,
    private val relayUrl: String,
) : AndroidViewModel(application) {
    private val client = RelayClient(baseUrl = relayUrl)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private var socket: SessionSocket? = null
    private var socketJob: Job? = null
    private var reconnectJob: Job? = null
    private var userClosed: Boolean = false

    fun setToken(token: String) {
        _state.update { it.copy(userToken = token) }
    }

    fun setModel(model: String) {
        _state.update {
            val supported = effortsFor(model)
            val nextEffort = if (it.effort in supported) it.effort else EFFORT_DEFAULT
            it.copy(model = model, effort = nextEffort)
        }
    }

    fun setEffort(effort: String) {
        _state.update { it.copy(effort = effort) }
    }

    fun setPermissions(mode: PermissionsMode) {
        _state.update { it.copy(permissions = mode) }
    }

    fun resolveApproval(decision: String) {
        val pending = _state.value.pendingApproval ?: return
        val sock = socket ?: return
        val frame = Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("type", "approval-response")
                put("approval_id", pending.approvalId)
                put("decision", decision)
            },
        )
        sock.sendJson(frame)
        _state.update { it.copy(pendingApproval = null) }
    }

    fun dismissSlashFeedback() {
        _state.update { it.copy(slashFeedback = null) }
    }

    /** Fire a turn-interrupt frame. Daemon translates into codex turn/interrupt. */
    fun interruptTurn() {
        val sock = socket ?: return
        val sid = _state.value.session?.sessionId ?: return
        val frame = Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("type", "turn-interrupt")
                put("session_id", sid)
            },
        )
        sock.sendJson(frame)
    }

    fun selectHost(id: String) {
        _state.update { it.copy(selectedHostId = id) }
    }

    fun goToHosts() {
        closeSession()
        _state.update { it.copy(screen = Screen.Hosts, threads = emptyList()) }
    }

    fun goToThreads() {
        _state.update { it.copy(screen = Screen.Threads) }
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

    /** Tap on a host → show threads screen, load its prior sessions. */
    fun openHost(host: Host) {
        if (!host.online) {
            _state.update { it.copy(error = "${host.nickname} is offline") }
            return
        }
        _state.update {
            it.copy(
                selectedHostId = host.id,
                screen = Screen.Threads,
                threads = emptyList(),
                threadsLoading = true,
                error = null,
            )
        }
        refreshThreads()
    }

    fun refreshThreads() {
        val target = _state.value.selectedHostId ?: return
        viewModelScope.launch {
            _state.update { it.copy(threadsLoading = true) }
            try {
                val ts = client.listThreads(_state.value.userToken, target, limit = 25)
                _state.update { it.copy(threads = ts, threadsLoading = false) }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        threadsLoading = false,
                        error = t.message ?: "threads failed",
                    )
                }
            }
        }
    }

    fun openSession(resumeThreadId: String? = null) {
        val target = _state.value.selectedHostId ?: return
        closeSession()
        userClosed = false
        _state.update {
            it.copy(
                screen = Screen.Session,
                status = Status.Opening,
                error = null,
                events = emptyList(),
                session = null,
                // Plan mode is a per-session toggle on the daemon side
                // (each adapter has its own _next_collab_mode). The UI
                // banner has to reset too so it doesn't claim plan mode
                // is active in a brand-new thread that hasn't been put
                // in plan mode yet.
                planMode = false,
                pendingApproval = null,
                slashFeedback = null,
                pendingImages = emptyList(),
            )
        }
        viewModelScope.launch {
            val sid = try {
                client.openSession(_state.value.userToken, target, resumeThreadId = resumeThreadId)
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
            attachSocket(sid)
        }
    }

    private fun attachSocket(sid: String) {
        val sock = SessionSocket(relayUrl, _state.value.userToken, sid)
        socket = sock
        socketJob?.cancel()
        socketJob = viewModelScope.launch {
            sock.events.collect { ev ->
                when (ev) {
                    is SocketEvent.Frame -> handleFrame(ev.text)
                    is SocketEvent.Closed -> handleDropped(sid, "closed")
                    is SocketEvent.Failure -> handleDropped(sid, ev.throwable.message ?: "socket error")
                }
            }
        }
    }

    private fun handleDropped(sid: String, reason: String) {
        if (userClosed) {
            _state.update { it.copy(status = Status.Disconnected, pending = false) }
            return
        }
        _state.update {
            it.copy(
                status = Status.Disconnected,
                pending = false,
                error = "reconnecting… ($reason)",
            )
        }
        scheduleReconnect(sid)
    }

    /**
     * Backoff reconnect: try 5× with 1s → 2s → 4s → 8s → 16s delays,
     * then give up. Any frame received will clear the error banner.
     */
    private fun scheduleReconnect(sid: String) {
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            val delays = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L)
            for ((attempt, d) in delays.withIndex()) {
                if (userClosed) return@launch
                delay(d)
                if (userClosed) return@launch
                _state.update {
                    it.copy(
                        status = Status.Connecting,
                        error = "reconnecting (try ${attempt + 1}/${delays.size})…",
                    )
                }
                attachSocket(sid)
                // Wait briefly for attach / frames to arrive.
                delay(2_000L)
                if (_state.value.status == Status.Connected) {
                    _state.update { it.copy(error = null) }
                    return@launch
                }
            }
            _state.update {
                it.copy(
                    status = Status.Error,
                    error = "disconnected — tap to reload the thread",
                )
            }
        }
    }

    /** Force a reconnect now, without waiting for the backoff timer. */
    fun reconnectNow() {
        val sid = _state.value.session?.sessionId ?: return
        userClosed = false
        reconnectJob?.cancel()
        attachSocket(sid)
    }

    fun sendTurn(text: String) {
        val input = text.trim()
        val attachments = _state.value.pendingImages
        if (input.isEmpty() && attachments.isEmpty()) return
        val sock = socket ?: return
        // Slash command shortcut: `/cmd [args…]` is routed as a separate
        // frame type, never as a prompt to the model.
        if (attachments.isEmpty() && input.startsWith("/")) {
            val bare = input.substring(1).trim()
            val cmd = bare.substringBefore(' ').lowercase()
            if (cmd.isNotEmpty()) {
                val frame = Json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("type", "slash-command")
                        put("command", cmd)
                    },
                )
                sock.sendJson(frame)
                if (cmd == "plan") _state.update { it.copy(planMode = true) }
                if (cmd == "default") _state.update { it.copy(planMode = false) }
                return
            }
        }
        val userId = "u-${UUID.randomUUID().toString().take(8)}"
        _state.update {
            it.copy(
                events = it.events + UiEvent.User(
                    id = userId,
                    text = input,
                    imageUris = attachments.map { a -> a.uri },
                ),
                pending = true,
                pendingImages = emptyList(),
            )
        }
        val model = _state.value.model.trim()
        val effort = _state.value.effort.trim()
        val perms = _state.value.permissions.wire
        val frame = Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("type", "turn-start")
                put("input", input)
                if (model.isNotEmpty()) put("model", model)
                if (effort.isNotEmpty() && effort != "none") put("effort", effort)
                put("permissions", perms)
                if (attachments.isNotEmpty()) {
                    put("images", buildJsonArray {
                        attachments.forEach { img ->
                            addJsonObject {
                                put("mime", img.mime)
                                put("data", img.base64)
                            }
                        }
                    })
                }
            },
        )
        sock.sendJson(frame)
    }

    /** Called from the UI when the user picks an image. Handles reading +
     *  base64-encoding off the main thread. */
    fun attachImage(uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                val (bytes, mime) = withContext(Dispatchers.IO) {
                    val resolved = app.contentResolver.getType(uri) ?: "image/jpeg"
                    val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("empty stream for $uri")
                    bytes to resolved
                }
                val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                val label = uri.lastPathSegment?.substringAfterLast('/') ?: "image"
                _state.update {
                    it.copy(
                        pendingImages = it.pendingImages + PendingImage(
                            uri = uri.toString(),
                            mime = mime,
                            base64 = b64,
                            label = label.take(32),
                        ),
                    )
                }
            } catch (t: Throwable) {
                _state.update { it.copy(error = "image: ${t.message ?: "read failed"}") }
            }
        }
    }

    fun removeImage(index: Int) {
        _state.update { s ->
            if (index !in s.pendingImages.indices) s
            else s.copy(pendingImages = s.pendingImages.toMutableList().apply { removeAt(index) })
        }
    }

    fun closeSession() {
        userClosed = true
        reconnectJob?.cancel()
        reconnectJob = null
        socket?.close()
        socket = null
        socketJob?.cancel()
        socketJob = null
        _state.update {
            it.copy(
                status = Status.Idle,
                pending = false,
                planMode = false,
                pendingApproval = null,
                slashFeedback = null,
            )
        }
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
                val replayed = data["replayed"]?.let {
                    (it as? JsonPrimitive)?.contentOrNull == "true" || it.toString() == "true"
                } ?: false
                val next: UiEvent = when (itemType) {
                    "agent_reasoning" -> UiEvent.Reasoning(
                        id = itemId,
                        text = data.string("text") ?: "",
                        completed = replayed,
                    )
                    "agent_message" -> UiEvent.Agent(
                        id = itemId,
                        text = data.string("text") ?: "",
                        completed = replayed,
                    )
                    "tool_call" -> UiEvent.Tool(
                        id = itemId,
                        tool = data.string("tool") ?: "tool",
                        command = data.obj("args")?.string("command") ?: "",
                        output = data.string("output") ?: "",
                        completed = replayed,
                    )
                    "user_message" -> UiEvent.User(
                        id = itemId,
                        text = data.string("text") ?: "",
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

            "history-begin", "history-end", "thread-status" -> {
                // informational markers — consumers can render a divider later
            }

            "approval-request" -> {
                val approvalId = data.string("approval_id") ?: return
                val kind = data.string("kind") ?: "command"
                val decisions = (data["decisions"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    ?: listOf("accept", "acceptForSession", "decline", "cancel")
                _state.update {
                    it.copy(
                        pendingApproval = ApprovalPrompt(
                            approvalId = approvalId,
                            kind = kind,
                            reason = data.string("reason"),
                            command = data.string("command"),
                            cwd = data.string("cwd"),
                            decisions = decisions,
                        )
                    )
                }
            }

            "slash-ack" -> {
                val cmd = data.string("command") ?: "?"
                val ok = (data["ok"] as? JsonPrimitive)?.contentOrNull == "true"
                    || data["ok"]?.toString() == "true"
                val msg = data.string("message")
                val err = data.string("error")
                val text = when {
                    !ok && err != null -> "/$cmd failed: $err"
                    msg != null -> "/$cmd — $msg"
                    else -> "/$cmd ok"
                }
                _state.update { it.copy(slashFeedback = text) }
            }

            "collab-modes" -> {
                // For now just surface the available mode names.
                val names = (data["modes"] as? JsonArray)
                    ?.mapNotNull {
                        ((it as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull
                    }
                    ?: emptyList()
                _state.update {
                    it.copy(slashFeedback = "collab modes: ${names.joinToString(", ")}")
                }
            }
        }
    }

    override fun onCleared() {
        closeSession()
        super.onCleared()
    }

    companion object {
        fun factory(application: Application, relayUrl: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    RemotexViewModel(application, relayUrl) as T
            }
    }
}

// Tiny helpers so the dispatch code above doesn't drown in ?.jsonObject?.get(...) chains.
private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.obj(key: String): JsonObject? =
    (this[key] as? JsonElement) as? JsonObject
