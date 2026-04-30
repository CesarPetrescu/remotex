package app.remotex.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.remotex.model.FsEntry
import app.remotex.model.Host
import app.remotex.model.HostTelemetryData
import app.remotex.model.HostTelemetrySnapshot
import app.remotex.model.SearchResult
import app.remotex.model.SearchStage
import app.remotex.model.TelemetryHistory
import app.remotex.model.ThreadInfo
import app.remotex.model.UiEvent
import app.remotex.net.RelayClient
import app.remotex.net.SessionSocket
import app.remotex.net.SocketEvent
import app.remotex.service.RemotexEvents
import app.remotex.service.SessionForegroundService
import app.remotex.service.SessionNotifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
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
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import kotlin.math.min
import kotlin.random.Random

enum class Screen { Hosts, Threads, Files, Session, Search }

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
    val model: String = "",          // empty → codex default (gpt-5.5 at time of writing)
    val effort: String = "medium",   // none/minimal/low/medium/high/xhigh
    val threads: List<ThreadInfo> = emptyList(),
    val threadsLoading: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<SearchResult> = emptyList(),
    val searchLoading: Boolean = false,
    // Live pipeline state — populated as the relay's /api/search/stream
    // yields plan / signal / fused / rerank events. Empty when there's
    // no active search.
    val searchStages: List<SearchStage> = emptyList(),
    val browsePath: String = "",
    val browseEntries: List<FsEntry> = emptyList(),
    val browseLoading: Boolean = false,
    val pendingImages: List<PendingImage> = emptyList(),
    val permissions: PermissionsMode = PermissionsMode.Default,
    val pendingApproval: ApprovalPrompt? = null,
    val slashFeedback: String? = null,
    val planMode: Boolean = false,   // true after /plan, cleared on /default
    // True between thread-status:resuming and thread-status:resumed/resume-failed.
    // Codex can take a minute+ to re-hydrate large rollouts; the banner makes it
    // obvious the app isn't hung.
    val resuming: Boolean = false,
    val resumingSinceMs: Long = 0L,
    // Cumulative token usage for this session. Updated when the daemon
    // forwards a thread/tokenUsage/updated frame. Reset on session open.
    val tokensInput: Long = 0L,
    val tokensOutput: Long = 0L,
    val tokensCached: Long = 0L,
    val tokensReasoning: Long = 0L,
    val hostTelemetry: Map<String, HostTelemetrySnapshot> = emptyMap(),
    // Picker list. Falls back to the embedded MODEL_OPTIONS list below
    // until GET /api/models replaces it on first load.
    val modelOptions: List<ModelOption> = MODEL_OPTIONS,
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
    ModelOption("gpt-5.5", "gpt-5.5", "newest frontier",
        listOf(EFFORT_DEFAULT, "low", "medium", "high", "xhigh")),
    ModelOption("gpt-5.4", "gpt-5.4", "frontier",
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
fun effortsFor(modelId: String, options: List<ModelOption> = MODEL_OPTIONS): List<String> =
    options.firstOrNull { it.id == modelId }?.efforts ?: ALL_EFFORTS

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
    private var reconnectAttempt: Int = 0
    private var userClosed: Boolean = false
    private var telemetryJob: Job? = null
    private var telemetryHostId: String? = null

    fun setToken(token: String) {
        _state.update { it.copy(userToken = token) }
    }

    init {
        // Fetch the canonical model list from the relay once. Failure is
        // silent — the embedded fallback in MODEL_OPTIONS keeps the picker
        // populated.
        viewModelScope.launch {
            try {
                val remote = client.listModels()
                if (remote.isNotEmpty()) {
                    val converted = remote.map {
                        ModelOption(
                            id = it.id,
                            label = it.label,
                            hint = it.hint,
                            efforts = if (it.efforts.isEmpty()) ALL_EFFORTS else it.efforts,
                        )
                    }
                    _state.update { it.copy(modelOptions = converted) }
                }
            } catch (_: Throwable) {
                // Keep fallback list; not fatal.
            }
        }
        observePendingForNotifications()
        observeNotificationActions()
    }

    /**
     * Watches `state.pending` for transitions: rising edge starts the
     * foreground service (so the OS keeps the WS alive while backgrounded
     * and the user sees a persistent "running" notification); falling
     * edge stops it. On the falling edge, if the app is *not* in the
     * foreground, also post a one-shot "agent done" notification.
     */
    private fun observePendingForNotifications() {
        viewModelScope.launch {
            var prevPending = false
            _state.collect { s ->
                val nowPending = s.pending
                if (nowPending && !prevPending) {
                    val (title, hostNick) = currentChatLabel(s)
                    SessionForegroundService.start(
                        ctx = getApplication(),
                        chatTitle = title,
                        hostNickname = hostNick,
                        hostId = s.session?.hostId,
                        threadId = s.session?.let { extractThreadId(it.sessionId, s) },
                    )
                } else if (!nowPending && prevPending) {
                    SessionForegroundService.stop(getApplication())
                    if (!isAppInForeground()) {
                        val (title, hostNick) = currentChatLabel(s)
                        SessionNotifier.postDoneNotification(
                            ctx = getApplication(),
                            chatTitle = title,
                            hostNickname = hostNick,
                            hostId = s.session?.hostId,
                            threadId = s.session?.let { extractThreadId(it.sessionId, s) },
                            tokensIn = s.tokensInput + s.tokensCached,
                            tokensOut = s.tokensOutput + s.tokensReasoning,
                        )
                    }
                }
                prevPending = nowPending
            }
        }
    }

    private fun observeNotificationActions() {
        viewModelScope.launch {
            RemotexEvents.cancelTurn.collect { interruptTurn() }
        }
        viewModelScope.launch {
            RemotexEvents.openSession.collect { (hostId, threadId) ->
                _state.update { it.copy(selectedHostId = hostId) }
                openSession(resumeThreadId = threadId, hostId = hostId)
            }
        }
    }

    private fun isAppInForeground(): Boolean = try {
        ProcessLifecycleOwner.get().lifecycle.currentState
            .isAtLeast(Lifecycle.State.RESUMED)
    } catch (_: Throwable) {
        true  // safer to skip the notification than to spam if lifecycle isn't ready
    }

    /** Returns (chatTitle, hostNickname) for notification copy. */
    private fun currentChatLabel(s: UiState): Pair<String, String> {
        val hostNick = s.hosts.firstOrNull { it.id == s.session?.hostId }?.nickname
            ?: s.session?.hostId?.take(12) ?: "host"
        // Match thread by best-known id; otherwise show the session prefix.
        val threadId = s.session?.let { extractThreadId(it.sessionId, s) }
        val chatTitle = threadId?.let { tid ->
            s.threads.firstOrNull { it.id == tid }?.let { thread ->
                thread.title?.takeIf { it.isNotBlank() } ?: thread.preview.take(40)
            }
        } ?: "current chat"
        return chatTitle to hostNick
    }

    /**
     * The current SessionInfo doesn't carry the codex thread id directly,
     * so we infer it from the threads list (most-recently opened thread
     * for this host). If we have no match, return null and notification
     * deep-links fall back to "open the app at last screen".
     */
    private fun extractThreadId(sessionId: String, s: UiState): String? {
        // openSession stashes the resume_thread_id into our local
        // resumingTarget; we don't currently track it on SessionInfo,
        // so fall back to whatever thread we last resumed.
        return _lastResumeThreadId
    }

    private var _lastResumeThreadId: String? = null

    fun setModel(model: String) {
        _state.update {
            val supported = effortsFor(model, it.modelOptions)
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
        startTelemetryPoll(id)
    }

    /** Poll /api/hosts/{id}/telemetry every 3s. Replaces any prior job. */
    private fun startTelemetryPoll(hostId: String) {
        if (telemetryHostId == hostId && telemetryJob?.isActive == true) return
        telemetryHostId = hostId
        telemetryJob?.cancel()
        telemetryJob = viewModelScope.launch {
            while (true) {
                val targetHost = telemetryHostId ?: break
                try {
                    val snap = client.getHostTelemetry(_state.value.userToken, targetHost)
                    val data = snap.data
                    if (data != null) applyTelemetry(targetHost, data)
                } catch (_: Throwable) {
                    // Transient failures are benign; next tick retries.
                }
                delay(3_000L)
            }
        }
    }

    private fun applyTelemetry(hostId: String, data: HostTelemetryData) {
        _state.update { s ->
            val prev = s.hostTelemetry[hostId]
            val history = (prev?.history ?: TelemetryHistory()).push(data)
            val snapshot = HostTelemetrySnapshot(
                data = data,
                history = history,
                lastUpdateMs = System.currentTimeMillis(),
            )
            s.copy(hostTelemetry = s.hostTelemetry + (hostId to snapshot))
        }
    }

    fun goToHosts() {
        closeSession()
        _state.update { it.copy(screen = Screen.Hosts, threads = emptyList()) }
    }

    fun goToThreads() {
        _state.update { it.copy(screen = Screen.Threads) }
    }

    fun goToSearch() {
        _state.update { it.copy(screen = Screen.Search) }
    }

    fun setSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    private var searchStreamJob: Job? = null

    fun searchChats() {
        val query = _state.value.searchQuery.trim()
        if (query.isEmpty()) {
            _state.update { it.copy(searchResults = emptyList(), searchLoading = false, searchStages = emptyList()) }
            searchStreamJob?.cancel()
            return
        }
        searchStreamJob?.cancel()
        _state.update {
            it.copy(
                searchLoading = true,
                error = null,
                searchStages = emptyList(),
                searchResults = emptyList(),
            )
        }
        searchStreamJob = client.searchChatsStream(
            scope = viewModelScope,
            userToken = _state.value.userToken,
            query = query,
            limit = 20,
            onEvent = { event -> handleSearchStreamEvent(event) },
            onDone = { err ->
                _state.update {
                    it.copy(
                        searchLoading = false,
                        error = err?.message?.takeIf { e -> e.isNotBlank() && it.searchResults.isEmpty() }
                            ?: it.error,
                    )
                }
            },
        )
    }

    private fun handleSearchStreamEvent(event: JsonObject) {
        val type = event["type"]?.jsonPrimitive?.contentOrNull ?: return
        when (type) {
            "plan" -> {
                val stages = (event["stages"] as? JsonArray).orEmpty().mapNotNull { s ->
                    val obj = s as? JsonObject ?: return@mapNotNull null
                    SearchStage(
                        name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "pending",
                    )
                }
                _state.update { it.copy(searchStages = stages) }
            }
            "signal" -> {
                val name = event["name"]?.jsonPrimitive?.contentOrNull ?: return
                val elapsedMs = event["elapsed_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                val count = event["count"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                val results = parseSearchResults(event["results"])
                _state.update {
                    it.copy(
                        searchStages = patchStage(it.searchStages, name, "done", elapsedMs, count),
                        searchResults = if (results.isNotEmpty()) results else it.searchResults,
                    )
                }
            }
            "fused" -> {
                val results = parseSearchResults(event["results"])
                if (results.isNotEmpty()) {
                    _state.update { it.copy(searchResults = results) }
                }
            }
            "rerank_start" -> {
                val candidates = event["candidates"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                _state.update {
                    it.copy(searchStages = patchStage(it.searchStages, "rerank", "running", null, candidates))
                }
            }
            "rerank" -> {
                val elapsedMs = event["elapsed_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                val results = parseSearchResults(event["results"])
                _state.update {
                    it.copy(
                        searchStages = patchStage(it.searchStages, "rerank", "done", elapsedMs, null),
                        searchResults = if (results.isNotEmpty()) results else it.searchResults,
                    )
                }
            }
            "rerank_error" -> {
                val msg = event["message"]?.jsonPrimitive?.contentOrNull ?: "rerank failed"
                _state.update {
                    it.copy(searchStages = patchStage(it.searchStages, "rerank", "error", null, null, msg))
                }
            }
            "done" -> {
                val results = parseSearchResults(event["results"])
                _state.update {
                    it.copy(
                        searchResults = if (results.isNotEmpty()) results else it.searchResults,
                        searchLoading = false,
                    )
                }
            }
        }
    }

    private fun patchStage(
        stages: List<SearchStage>,
        name: String,
        status: String,
        elapsedMs: Long?,
        count: Int?,
        error: String? = null,
    ): List<SearchStage> {
        if (stages.none { it.name == name }) {
            return stages + SearchStage(name, status, elapsedMs, count, error)
        }
        return stages.map { stage ->
            if (stage.name != name) return@map stage
            stage.copy(
                status = status,
                elapsedMs = elapsedMs ?: stage.elapsedMs,
                count = count ?: stage.count,
                error = error ?: stage.error,
            )
        }
    }

    private fun parseSearchResults(element: JsonElement?): List<SearchResult> {
        val arr = element as? JsonArray ?: return emptyList()
        if (arr.isEmpty()) return emptyList()
        return try {
            json.decodeFromJsonElement(
                kotlinx.serialization.builtins.ListSerializer(SearchResult.serializer()),
                arr,
            )
        } catch (_: Throwable) {
            emptyList()
        }
    }

    // --- workspace files (in-chat panel) ---

    /** List the contents of a directory on the active host. Used by the
     *  in-chat workspace files panel; navigates within cwd. */
    suspend fun listWorkspace(path: String): List<FsEntry> {
        val hostId = _state.value.session?.hostId ?: _state.value.selectedHostId
            ?: error("no host selected")
        val token = _state.value.userToken
        return withContext(Dispatchers.IO) {
            client.readDirectory(token, hostId, path).entries
        }
    }

    suspend fun deleteWorkspaceFile(path: String) {
        val hostId = _state.value.session?.hostId ?: error("no host selected")
        client.deleteFile(_state.value.userToken, hostId, path)
    }

    suspend fun renameWorkspaceFile(from: String, to: String) {
        val hostId = _state.value.session?.hostId ?: error("no host selected")
        client.renameFile(_state.value.userToken, hostId, from, to)
    }

    suspend fun readWorkspaceFile(path: String): RelayClient.WorkspaceFile {
        val hostId = _state.value.session?.hostId ?: error("no host selected")
        return client.readFile(_state.value.userToken, hostId, path)
    }

    suspend fun uploadWorkspaceFile(targetDir: String, fileName: String, bytes: ByteArray, mime: String) {
        val hostId = _state.value.session?.hostId ?: error("no host selected")
        client.uploadFile(_state.value.userToken, hostId, targetDir, fileName, bytes, mime)
    }

    fun openSearchResult(result: SearchResult) {
        val threadId = result.threadId
        if (threadId.isNullOrBlank()) {
            _state.update { it.copy(error = "This result has no resumable Codex thread yet.") }
            return
        }
        _state.update { it.copy(selectedHostId = result.hostId) }
        openSession(resumeThreadId = threadId, cwd = result.cwd, hostId = result.hostId)
    }

    fun goToFiles(initialPath: String? = null) {
        val start = initialPath?.ifBlank { null }
            ?: _state.value.browsePath.ifBlank { null }
            ?: "/"
        _state.update { it.copy(screen = Screen.Files) }
        browseDir(start)
    }

    fun browseDir(path: String) {
        val target = _state.value.selectedHostId ?: return
        viewModelScope.launch {
            _state.update { it.copy(browseLoading = true, browsePath = path, error = null) }
            try {
                val resp = client.readDirectory(_state.value.userToken, target, path)
                _state.update {
                    it.copy(
                        browsePath = resp.path,
                        browseEntries = resp.entries.sortedWith(
                            compareByDescending<FsEntry> { e -> e.isDirectory }.thenBy { e -> e.fileName.lowercase() }
                        ),
                        browseLoading = false,
                    )
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(browseLoading = false, error = t.message ?: "readDir failed")
                }
            }
        }
    }

    fun browseUp() {
        val p = _state.value.browsePath
        if (p.isEmpty() || p == "/") return
        val parent = p.trimEnd('/').substringBeforeLast('/', "/").ifEmpty { "/" }
        browseDir(parent)
    }

    fun createFolder(name: String) {
        val target = _state.value.selectedHostId ?: return
        val parent = _state.value.browsePath.ifEmpty { "/" }
        viewModelScope.launch {
            try {
                client.mkdir(_state.value.userToken, target, parent, name)
                browseDir(parent)
            } catch (t: Throwable) {
                _state.update { it.copy(error = t.message ?: "mkdir failed") }
            }
        }
    }

    fun startSessionInCurrentPath() {
        val path = _state.value.browsePath.ifEmpty { null }
        openSession(resumeThreadId = null, cwd = path)
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val hosts = client.listHosts(_state.value.userToken)
                _state.update { it.copy(hosts = hosts, loading = false) }
                // Auto-select first online host so the telemetry panel
                // populates without an extra tap.
                if (_state.value.selectedHostId == null) {
                    hosts.firstOrNull { it.online }?.let { h ->
                        _state.update { it.copy(selectedHostId = h.id) }
                        startTelemetryPoll(h.id)
                    }
                } else {
                    _state.value.selectedHostId?.let { startTelemetryPoll(it) }
                }
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

    fun openSession(resumeThreadId: String? = null, cwd: String? = null, hostId: String? = null) {
        val target = hostId ?: _state.value.selectedHostId ?: return
        closeSession()
        userClosed = false
        // Track for the foreground service / done notification — they
        // need the thread id to look up the title and deep-link back.
        _lastResumeThreadId = resumeThreadId
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
                resuming = false,
                resumingSinceMs = 0L,
                tokensInput = 0L,
                tokensOutput = 0L,
                tokensCached = 0L,
                tokensReasoning = 0L,
            )
        }
        viewModelScope.launch {
            val sid = try {
                client.openSession(
                    _state.value.userToken,
                    target,
                    resumeThreadId = resumeThreadId,
                    cwd = cwd,
                )
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
        socket?.close()
        socketJob?.cancel()
        val sock = SessionSocket(relayUrl, _state.value.userToken, sid)
        socket = sock
        socketJob = viewModelScope.launch {
            sock.events.collect { ev ->
                if (socket !== sock) return@collect
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
     * Capped exponential backoff with jitter. We keep trying until the user
     * explicitly leaves the session; mobile networks often disappear for
     * longer than a fixed retry budget.
     */
    private fun scheduleReconnect(sid: String) {
        reconnectJob?.cancel()
        val attempt = reconnectAttempt
        val base = min(30_000L, 1_000L shl min(attempt, 5))
        val jitter = Random.nextLong(0, min(1_000L, base / 4) + 1)
        val delayMs = base + jitter
        reconnectAttempt = attempt + 1
        _state.update {
            it.copy(
                status = Status.Disconnected,
                error = "reconnecting in ${(delayMs + 999) / 1000}s…",
            )
        }
        reconnectJob = viewModelScope.launch {
            delay(delayMs)
            if (userClosed) return@launch
            _state.update {
                it.copy(
                    status = Status.Connecting,
                    error = "reconnecting…",
                )
            }
            attachSocket(sid)
        }
    }

    /** Force a reconnect now, without waiting for the backoff timer. */
    fun reconnectNow() {
        val sid = _state.value.session?.sessionId ?: return
        userClosed = false
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempt = 0
        _state.update { it.copy(status = Status.Connecting, error = "reconnecting…") }
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
            // Forward everything after the command as `args`, so /cd <path>
            // (and future commands that take arguments) reach the daemon.
            val args = if (' ' in bare) bare.substringAfter(' ').trim() else ""
            if (cmd.isNotEmpty()) {
                val frame = Json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("type", "slash-command")
                        put("command", cmd)
                        if (args.isNotEmpty()) put("args", args)
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
        reconnectAttempt = 0
        socket?.close(endSession = true)
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
                resuming = false,
                resumingSinceMs = 0L,
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
            "attached" -> {
                reconnectAttempt = 0
                _state.update { it.copy(status = Status.Connecting, error = null) }
            }
            "pong" -> Unit
            "session-closed" -> _state.update {
                it.copy(status = Status.Disconnected, pending = false)
            }
            "session-event" -> {
                val ev = msg["event"]?.jsonObject ?: return
                val kind = ev.string("kind") ?: return
                val data = ev["data"]?.jsonObject ?: JsonObject(emptyMap())
                applyEvent(kind, data)
            }
            "host-telemetry" -> {
                val hostId = msg.string("host_id") ?: return
                val rawData = msg["data"]?.jsonObject ?: return
                val data = try {
                    json.decodeFromJsonElement(HostTelemetryData.serializer(), rawData)
                } catch (_: Throwable) {
                    return
                }
                applyTelemetry(hostId, data)
            }
            "error" -> _state.update {
                it.copy(error = msg.string("error") ?: "relay error")
            }
        }
    }

    private fun applyEvent(kind: String, data: JsonObject) {
        when (kind) {
            "session-started" -> {
                reconnectAttempt = 0
                val transport = data.string("transport") ?: "stdio"
                val resuming = data["resuming"]?.jsonPrimitive?.contentOrNull == "true"
                val readOnlyHistory = transport == "history"
                _state.update {
                    it.copy(
                        status = when {
                            readOnlyHistory -> Status.Error
                            resuming -> Status.Connecting
                            else -> Status.Connected
                        },
                        error = when {
                            readOnlyHistory -> "Saved chat is history-only. Start a new session to continue."
                            resuming -> "Resuming saved chat…"
                            else -> null
                        },
                        session = it.session?.copy(
                            model = data.string("model") ?: it.session.model,
                            cwd = data.string("cwd") ?: it.session.cwd,
                        ),
                    )
                }
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
                        replayed = replayed,
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

            "thread-status" -> {
                when (data.string("status")) {
                    "resuming" -> {
                        _state.update {
                            it.copy(
                                resuming = true,
                                resumingSinceMs = System.currentTimeMillis(),
                            )
                        }
                    }
                    "resumed" -> {
                        reconnectAttempt = 0
                        _state.update {
                            it.copy(
                                status = Status.Connected,
                                error = null,
                                resuming = false,
                                resumingSinceMs = 0L,
                                session = it.session?.copy(
                                    model = data.string("model") ?: it.session.model,
                                    cwd = data.string("cwd") ?: it.session.cwd,
                                ),
                            )
                        }
                    }
                    "resume-failed" -> {
                        _state.update {
                            it.copy(
                                status = Status.Error,
                                pending = false,
                                resuming = false,
                                resumingSinceMs = 0L,
                                error = data.string("error") ?: "Saved chat could not be resumed.",
                            )
                        }
                    }
                }
            }

            "history-begin", "history-end" -> {
                // informational markers — consumers can render a divider later
            }

            "token-usage" -> {
                // Daemon flattens codex's payload to top-level fields.
                fun pickLong(key: String): Long? =
                    data[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                _state.update {
                    it.copy(
                        tokensInput     = pickLong("input")            ?: it.tokensInput,
                        tokensOutput    = pickLong("output")           ?: it.tokensOutput,
                        tokensCached    = pickLong("cached_input")     ?: it.tokensCached,
                        tokensReasoning = pickLong("reasoning_output") ?: it.tokensReasoning,
                    )
                }
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
        telemetryJob?.cancel()
        telemetryJob = null
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
