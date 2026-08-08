package app.remotex.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.remotex.model.FsEntry
import app.remotex.model.Host
import app.remotex.model.HostTelemetryData
import app.remotex.model.HostTelemetrySnapshot
import app.remotex.model.ModelInfo
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

enum class Screen { Hosts, Threads, Files, Session }

enum class Status { Idle, Opening, Connecting, Connected, Disconnected, Error }

data class SessionInfo(
    val sessionId: String,
    val hostId: String,
    val model: String? = null,
    val cwd: String? = null,
    val kind: String = "codex",
)

data class PendingImage(
    val uri: String,      // content:// URI for thumbnail
    val mime: String,
    val base64: String,   // encoded payload for the wire
    val label: String,    // short filename for display
    val bytes: Long = 0L, // decoded size, for the attachment ceiling
)

data class ApprovalPrompt(
    val approvalId: String,
    val kind: String,          // "command" or "file_change"
    val reason: String?,
    val command: String?,
    val cwd: String?,
    val permissions: JsonElement? = null,
    val decisions: List<String>,
    /** Relay-assigned queue position; null on live request frames. */
    val order: Long? = null,
)

/** Codex's request_user_input prompt — shown as a modal dialog so the
 *  user can answer multi-question option/notes pickers. Mirrors
 *  codex-rs/protocol/src/request_user_input.rs. */
data class UserInputQuestionOption(
    val label: String,
    val description: String = "",
)
data class UserInputQuestion(
    val id: String,
    val header: String = "",
    val question: String = "",
    val isOther: Boolean = false,
    val isSecret: Boolean = false,
    val options: List<UserInputQuestionOption> = emptyList(),
)
data class UserInputPrompt(
    val callId: String,
    val turnId: String? = null,
    val questions: List<UserInputQuestion>,
    /** Relay-assigned queue position; null on live request frames. */
    val order: Long? = null,
)

data class ThreadGoal(
    val threadId: String = "",
    val objective: String = "",
    val status: String = "",
    val tokenBudget: Long? = null,
    val tokensUsed: Long = 0L,
    val timeUsedSeconds: Long = 0L,
    val createdAt: String? = null,
    val updatedAt: String? = null,
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
    val browsePath: String = "",
    val browseEntries: List<FsEntry> = emptyList(),
    val browseLoading: Boolean = false,
    val favorites: List<String> = emptyList(),  // pinned cwd paths (per host)
    val recents: List<String> = emptyList(),    // recently-used cwd paths (per host)
    val pendingImages: List<PendingImage> = emptyList(),
    val permissions: PermissionsMode = PermissionsMode.Default,
    /** Every unanswered approval, in arrival order (contract F). A second
     *  concurrent prompt queues behind the first instead of replacing it. */
    val pendingApprovals: List<ApprovalPrompt> = emptyList(),
    /** Codex's multi-choice questions (plan mode, ambiguous tools), also
     *  queued. Answering the head reveals the next. */
    val pendingUserInputs: List<UserInputPrompt> = emptyList(),
    val slashFeedback: String? = null,
    val planMode: Boolean = false,   // true after /plan, cleared on /default
    val goal: ThreadGoal? = null,
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
    // Picker list, most-specific source first (contract B): the selected
    // host's own list, then the relay's static /api/models, then the
    // embedded MODEL_OPTIONS fallback below.
    val modelOptions: List<ModelOption> = MODEL_OPTIONS,
) {
    // Heads are derived, never stored, so they can't drift from the queue
    // they front. The dialogs render these; the queues own the ordering.
    val pendingApproval: ApprovalPrompt? get() = pendingApprovals.firstOrNull()
    val pendingUserInput: UserInputPrompt? get() = pendingUserInputs.firstOrNull()
}

/**
 * Reasoning effort levels surfaced to the UI. Empty string = "don't
 * override" (codex falls back to the model's default, usually medium).
 * Per-model support filters this set — see [ModelOption.efforts].
 */
const val EFFORT_DEFAULT = ""
val ALL_EFFORTS = listOf(EFFORT_DEFAULT, "low", "medium", "high", "xhigh")

// Client-side ceiling for anything that travels as bytes — image
// attachments (base64 inside one turn-start frame) and workspace uploads.
// Mirrors REMOTEX_MAX_FILE_BYTES on the relay and daemon (25 MB default)
// and apps/web/src/config.js: over it the request is refused, or the
// websocket frame is dropped and takes the socket with it.
const val MAX_FILE_BYTES = 25L * 1024 * 1024

fun formatBytes(n: Long): String = when {
    n >= 1024 * 1024 -> "%.1f MB".format(n / (1024.0 * 1024.0))
    n >= 1024 -> "${n / 1024} KB"
    else -> "$n B"
}

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
    ModelOption("gpt-5.3-codex-spark", "gpt-5.3 · codex spark", "ultra-fast coding",
        listOf(EFFORT_DEFAULT, "low", "medium", "high", "xhigh")),
)

/** Effort list the UI should show given the currently-picked model. */
fun effortsFor(modelId: String, options: List<ModelOption> = MODEL_OPTIONS): List<String> =
    options.firstOrNull { it.id == modelId }?.efforts ?: ALL_EFFORTS

// --- pending-prompt queues (contract F) -------------------------------------
//
// Mirrors apps/web/src/hooks/useRemotex.js. Prompts are keyed by
// approval_id / call_id so a replayed or duplicated frame updates in place
// instead of double-inserting. The head is what the UI renders; answering
// it pops the head and reveals the next.

fun <T : Any> enqueuePrompt(queue: List<T>, prompt: T?, keyOf: (T) -> String): List<T> {
    if (prompt == null) return queue
    val key = keyOf(prompt)
    val idx = queue.indexOfFirst { keyOf(it) == key }
    if (idx < 0) return queue + prompt
    // Same prompt again (reconnect replay): refresh the payload, keep the slot.
    return queue.toMutableList().also { it[idx] = prompt }
}

fun <T : Any> dequeuePrompt(queue: List<T>, key: String?, keyOf: (T) -> String): List<T> {
    // A resolution frame without an id can only mean the head — that is the
    // one every client is rendering.
    if (key.isNullOrEmpty()) return queue.drop(1)
    return queue.filter { keyOf(it) != key }
}

/**
 * Merge a relay snapshot into the local queue. The snapshot decides
 * MEMBERSHIP (a prompt another client answered is gone from it) and, when
 * it carries the relay's `order`, ORDER too — that is what puts a claim the
 * relay handed back after a failed forward into its original slot rather
 * than behind a prompt that arrived later (contract F). Without `order`
 * the local order stands.
 */
fun <T : Any> reconcileQueue(
    queue: List<T>,
    incoming: List<T>,
    keyOf: (T) -> String,
    orderOf: (T) -> Long? = { null },
): List<T> {
    val byKey = LinkedHashMap<String, T>()
    incoming.forEach { byKey[keyOf(it)] = it }
    val kept = mutableListOf<T>()
    for (existing in queue) {
        val replacement = byKey.remove(keyOf(existing)) ?: continue
        kept += replacement
    }
    val merged = kept + byKey.values
    return if (merged.isNotEmpty() && merged.all { orderOf(it) != null }) {
        merged.sortedBy { orderOf(it)!! }
    } else {
        merged
    }
}

val approvalKey: (ApprovalPrompt) -> String = { it.approvalId }
val userInputKey: (UserInputPrompt) -> String = { it.callId }
val approvalOrder: (ApprovalPrompt) -> Long? = { it.order }
val userInputOrder: (UserInputPrompt) -> Long? = { it.order }

/** Both halves of a relay `pending-prompts` snapshot, already normalised. */
data class PromptSnapshot(
    val approvals: List<ApprovalPrompt> = emptyList(),
    val userInputs: List<UserInputPrompt> = emptyList(),
)

/** The relay's pending-prompts frame carries EVERY unanswered prompt, in
 *  arrival order. Both stay lists — a second concurrent prompt must never
 *  displace the first. */
fun normalizePromptSnapshot(frame: JsonObject): PromptSnapshot = PromptSnapshot(
    approvals = (frame["approvals"] as? JsonArray).orEmpty()
        .mapNotNull { it as? JsonObject }
        .mapNotNull { parseApprovalPrompt(it) },
    userInputs = (frame["user_inputs"] as? JsonArray).orEmpty()
        .mapNotNull { it as? JsonObject }
        .mapNotNull { parseUserInputPrompt(it) },
)

fun parseApprovalPrompt(data: JsonObject): ApprovalPrompt? {
    val approvalId = data.string("approval_id") ?: return null
    val decisions = (data["decisions"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        ?: listOf("accept", "acceptForSession", "decline", "cancel")
    return ApprovalPrompt(
        approvalId = approvalId,
        kind = data.string("kind") ?: "command",
        reason = data.string("reason"),
        command = data.string("command"),
        cwd = data.string("cwd"),
        permissions = data["permissions"],
        decisions = decisions,
        order = data.long("order"),
    )
}

fun parseUserInputPrompt(data: JsonObject): UserInputPrompt? {
    val callId = data.string("call_id") ?: return null
    val questionsArr = data["questions"] as? JsonArray ?: return null
    val questions = questionsArr.mapNotNull { qe ->
        val qo = qe as? JsonObject ?: return@mapNotNull null
        val qid = qo.string("id") ?: return@mapNotNull null
        val opts = (qo["options"] as? JsonArray).orEmpty().mapNotNull { oe ->
            val oo = oe as? JsonObject ?: return@mapNotNull null
            val label = oo.string("label") ?: return@mapNotNull null
            UserInputQuestionOption(label, oo.string("description").orEmpty())
        }
        UserInputQuestion(
            id = qid,
            header = qo.string("header").orEmpty(),
            question = qo.string("question").orEmpty(),
            isOther = qo["isOther"]?.jsonPrimitive?.contentOrNull == "true",
            isSecret = qo["isSecret"]?.jsonPrimitive?.contentOrNull == "true",
            options = opts,
        )
    }
    return UserInputPrompt(
        callId = callId,
        turnId = data.string("turn_id"),
        questions = questions,
        order = data.long("order"),
    )
}

class RemotexViewModel(
    application: Application,
    private val relayUrl: String,
) : AndroidViewModel(application) {
    private val client = RelayClient(baseUrl = relayUrl)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // Client-side cwd recents + favorites, persisted per host. No backend —
    // the relay never stores or scans anything (mirrors web folderHistory).
    private val folderPrefs by lazy {
        getApplication<Application>().getSharedPreferences("remotex.folders", Context.MODE_PRIVATE)
    }
    private fun favKey() = "fav.${_state.value.selectedHostId ?: "default"}"
    private fun recKey() = "rec.${_state.value.selectedHostId ?: "default"}"
    private fun loadList(key: String): List<String> =
        (folderPrefs.getString(key, "") ?: "").split("\n").filter { it.isNotBlank() }

    fun toggleFavorite(path: String) {
        val favs = loadList(favKey()).toMutableList()
        if (!favs.remove(path)) favs.add(0, path)
        folderPrefs.edit().putString(favKey(), favs.joinToString("\n")).apply()
        _state.update { it.copy(favorites = favs) }
    }

    private fun recordVisit(path: String) {
        // ponytail: recency only (most-recent-first, cap 12). Web uses frecency;
        // marginal on a single-user phone and the two stores aren't synced anyway.
        val rec = loadList(recKey()).toMutableList()
        rec.remove(path); rec.add(0, path)
        while (rec.size > 12) rec.removeAt(rec.lastIndex)
        folderPrefs.edit().putString(recKey(), rec.joinToString("\n")).apply()
    }

    private var socket: SessionSocket? = null
    private var socketJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt: Int = 0
    private var userClosed: Boolean = false
    private var telemetryJob: Job? = null
    private var telemetryHostId: String? = null
    private var modelFetchJob: Job? = null
    // Host whose model list is currently in state; keeps repeated host
    // selections from re-fetching the same list.
    private var modelOptionsHostId: String? = null
    private val clientId: String = "android-${UUID.randomUUID().toString().take(12)}"
    private val lastSeqBySession: MutableMap<String, Long> = mutableMapOf()

    fun setToken(token: String) {
        _state.update { it.copy(userToken = token) }
    }

    init {
        // No host picked yet, so this can only reach the relay's static
        // list; selecting a host re-asks that host (see [refreshModelOptions]).
        refreshModelOptions(null)
        observePendingForNotifications()
        observeNotificationActions()
    }

    /**
     * Model list, most-specific source first (contract B): what the selected
     * host's codex actually offers → the relay's static /api/models →
     * the embedded MODEL_OPTIONS already sitting in state. Every failure is
     * silent; the picker is never left empty.
     */
    private fun refreshModelOptions(hostId: String?) {
        if (hostId != null && hostId == modelOptionsHostId) return
        modelFetchJob?.cancel()
        modelFetchJob = viewModelScope.launch {
            if (hostId != null) {
                try {
                    if (applyModelOptions(client.listHostModels(_state.value.userToken, hostId))) {
                        modelOptionsHostId = hostId
                        return@launch
                    }
                } catch (_: Throwable) {
                    // Host offline / old relay without the route — fall through.
                    // modelOptionsHostId stays unset so a later selection retries.
                }
            }
            try {
                applyModelOptions(client.listModels())
            } catch (_: Throwable) {
                // Keep fallback list; not fatal.
            }
        }
    }

    /** Returns false (and changes nothing) for an empty list, so the caller
     *  can fall through to the next source. */
    private fun applyModelOptions(remote: List<ModelInfo>): Boolean {
        if (remote.isEmpty()) return false
        val converted = remote.map {
            ModelOption(
                id = it.id,
                label = it.label,
                hint = it.hint,
                efforts = if (it.efforts.isEmpty()) ALL_EFFORTS else it.efforts,
            )
        }
        // Same rule as the web MODEL_OPTIONS reducer: a host that doesn't
        // offer the currently-picked model (or effort) would reject the
        // turn, so fall back to "codex picks" rather than sending
        // something we already know is invalid.
        _state.update {
            val model = if (converted.any { m -> m.id == it.model }) it.model else ""
            val effort = if (it.effort in effortsFor(model, converted)) it.effort else EFFORT_DEFAULT
            it.copy(modelOptions = converted, model = model, effort = effort)
        }
        return true
    }

    /** Host selection side-effects: telemetry poll + host-scoped model list. */
    private fun onHostSelected(hostId: String) {
        startTelemetryPoll(hostId)
        refreshModelOptions(hostId)
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

    /** Public slash sender — used by composer's plan-chip + autocomplete. */
    fun sendSlash(cmd: String, args: String = "") {
        val sock = socket ?: return
        val frame = Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("type", "slash-command")
                put("command", cmd)
                if (args.isNotEmpty()) put("args", args)
            },
        )
        if (!sock.sendJson(frame)) {
            _state.update { it.copy(error = "socket is not connected") }
            return
        }
        if (cmd == "plan") _state.update { it.copy(planMode = true) }
        if (cmd == "default") _state.update { it.copy(planMode = false) }
    }

    fun refreshGoal() {
        sendGoalFrame(buildJsonObject { put("type", "goal-get") })
    }

    fun setGoal(objective: String, tokenBudget: Long? = null) {
        val cleaned = objective.trim()
        if (cleaned.isEmpty()) {
            _state.update { it.copy(error = "goal objective is required") }
            return
        }
        sendGoalFrame(
            buildJsonObject {
                put("type", "goal-set")
                put("objective", cleaned)
                put("status", "active")
                tokenBudget?.takeIf { it > 0L }?.let { put("token_budget", it) }
            },
        )
    }

    fun pauseGoal() {
        sendGoalFrame(buildJsonObject {
            put("type", "goal-set")
            put("status", "paused")
        })
    }

    fun resumeGoal() {
        sendGoalFrame(buildJsonObject {
            put("type", "goal-set")
            put("status", "active")
        })
    }

    fun clearGoal() {
        sendGoalFrame(buildJsonObject { put("type", "goal-clear") })
    }

    private fun sendGoalFrame(frame: JsonObject) {
        val sock = socket ?: run {
            _state.update { it.copy(error = "socket is not connected") }
            return
        }
        val raw = Json.encodeToString(JsonObject.serializer(), frame)
        if (!sock.sendJson(raw)) {
            _state.update { it.copy(error = "socket is not connected") }
        }
    }

    fun setEffort(effort: String) {
        _state.update { it.copy(effort = effort) }
    }

    fun setPermissions(mode: PermissionsMode) {
        _state.update { it.copy(permissions = mode) }
    }

    /** Answers the head of the approval queue; popping it reveals the next.
     *  If the relay can't deliver the answer it re-pushes a pending-prompts
     *  snapshot, which puts the prompt back. */
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
        if (sock.sendJson(frame)) {
            _state.update {
                it.copy(
                    pendingApprovals = dequeuePrompt(
                        it.pendingApprovals, pending.approvalId, approvalKey,
                    ),
                )
            }
        }
    }

    /** answers: questionId → list of answer strings. First entry is the
     *  selected option label (when options exist), second is freeform
     *  notes. Daemon normalises either flat-array or {answers:[]} on
     *  receive. */
    fun resolveUserInput(answers: Map<String, List<String>>) {
        val pending = _state.value.pendingUserInput ?: return
        val sock = socket ?: return
        val frame = Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("type", "user-input-response")
                put("call_id", pending.callId)
                put("answers", buildJsonObject {
                    answers.forEach { (qid, ans) ->
                        put(qid, buildJsonArray {
                            ans.forEach { add(JsonPrimitive(it)) }
                        })
                    }
                })
            },
        )
        if (sock.sendJson(frame)) {
            _state.update {
                it.copy(
                    pendingUserInputs = dequeuePrompt(
                        it.pendingUserInputs, pending.callId, userInputKey,
                    ),
                )
            }
        }
    }

    fun cancelUserInput() {
        // Empty answers → daemon sends {answers:{}} → codex marks every
        // question "skipped".
        resolveUserInput(emptyMap())
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
        onHostSelected(id)
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
        // Refused here rather than shipping bytes the relay answers with a
        // 413 (mirrors the web uploader).
        require(bytes.size <= MAX_FILE_BYTES) {
            "$fileName is ${formatBytes(bytes.size.toLong())} — the upload limit is " +
                formatBytes(MAX_FILE_BYTES)
        }
        client.uploadFile(_state.value.userToken, hostId, targetDir, fileName, bytes, mime)
    }

    fun goToFiles(initialPath: String? = null) {
        // Start the picker at the host's home / default cwd, not filesystem
        // root — matches the web picker (util/host.js hostHomePath).
        val host = _state.value.hosts.find { it.id == _state.value.selectedHostId }
        val start = initialPath?.ifBlank { null }
            ?: _state.value.browsePath.ifBlank { null }
            ?: host?.homeDir ?: host?.defaultCwd ?: "/"
        _state.update { it.copy(
            screen = Screen.Files,
            favorites = loadList(favKey()),
            recents = loadList(recKey()),
        ) }
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
        if (path != null) recordVisit(path)
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
                        onHostSelected(h.id)
                    }
                } else {
                    _state.value.selectedHostId?.let { onHostSelected(it) }
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
        // A2: switch the telemetry poll to the newly-selected host. Without
        // this, the poll stayed on whatever host refresh() auto-selected
        // and the threads screen showed "no telemetry yet" forever for any
        // other host the user tapped into.
        onHostSelected(host.id)
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

    fun openSession(
        resumeThreadId: String? = null,
        cwd: String? = null,
        hostId: String? = null,
    ) {
        val target = hostId ?: _state.value.selectedHostId ?: return
        detachSession()
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
                pendingApprovals = emptyList(),
                pendingUserInputs = emptyList(),
                slashFeedback = null,
                pendingImages = emptyList(),
                resuming = false,
                resumingSinceMs = 0L,
                tokensInput = 0L,
                tokensOutput = 0L,
                tokensCached = 0L,
                tokensReasoning = 0L,
                goal = null,
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
                    session = SessionInfo(
                        sessionId = sid,
                        hostId = target,
                    ),
                    status = Status.Connecting,
                )
            }
            attachSocket(sid)
        }
    }

    private fun attachSocket(sid: String) {
        socket?.close()
        socketJob?.cancel()
        val sock = SessionSocket(
            relayUrl,
            _state.value.userToken,
            sid,
            clientId = clientId,
            lastSeq = lastSeqBySession[sid] ?: 0L,
        )
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
                if (!sock.sendJson(frame)) {
                    _state.update { it.copy(error = "socket is not connected") }
                    return
                }
                if (cmd == "plan") _state.update { it.copy(planMode = true) }
                if (cmd == "default") _state.update { it.copy(planMode = false) }
                return
            }
        }
        val clientMessageId = "msg-${UUID.randomUUID().toString().take(8)}"
        val model = _state.value.model.trim()
        val effort = _state.value.effort.trim()
        val perms = _state.value.permissions.wire
        val frame = Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("type", "turn-start")
                put("input", input)
                put("client_message_id", clientMessageId)
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
        if (!sock.sendJson(frame)) {
            _state.update { it.copy(error = "socket is not connected") }
            return
        }
        _state.update {
            it.copy(
                pending = true,
                pendingImages = emptyList(),
            )
        }
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
                val already = _state.value.pendingImages.sumOf { it.bytes }
                if (already + bytes.size > MAX_FILE_BYTES) {
                    // Images ride inside a single turn-start frame, so the
                    // whole batch has to fit under the ceiling.
                    _state.update {
                        it.copy(
                            error = "image: ${formatBytes(bytes.size.toLong())} exceeds the " +
                                "${formatBytes(MAX_FILE_BYTES)} attachment limit",
                        )
                    }
                    return@launch
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
                            bytes = bytes.size.toLong(),
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
                pendingApprovals = emptyList(),
                pendingUserInputs = emptyList(),
                slashFeedback = null,
                resuming = false,
                resumingSinceMs = 0L,
                goal = null,
            )
        }
    }

    private fun detachSession() {
        userClosed = true
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempt = 0
        socket?.close(endSession = false)
        socket = null
        socketJob?.cancel()
        socketJob = null
    }

    // --- frame parsing ------------------------------------------------------

    private fun handleFrame(raw: String) {
        val msg = try {
            json.parseToJsonElement(raw).jsonObject
        } catch (_: Throwable) {
            return
        }
        val seq = msg["seq"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        val sid = msg.string("session_id") ?: _state.value.session?.sessionId
        if (seq != null && sid != null) {
            // Assigned, not max'd: seq is minted per relay process, so after
            // a redeploy the new stream restarts at 1. A running max would
            // hold the cursor above it forever and every later reconnect
            // would silently replay nothing.
            lastSeqBySession[sid] = seq
        }
        when (msg.string("type")) {
            "attached" -> {
                reconnectAttempt = 0
                _state.update { it.copy(status = Status.Connecting, error = null) }
            }
            "approval-resolved" -> {
                // Answered here or by a peer client — either way this prompt
                // leaves the queue and the next one becomes the head.
                val approvalId = msg.string("approval_id")
                _state.update {
                    it.copy(
                        pendingApprovals = dequeuePrompt(
                            it.pendingApprovals, approvalId, approvalKey,
                        ),
                    )
                }
            }
            "user-input-resolved" -> {
                val callId = msg.string("call_id")
                _state.update {
                    it.copy(
                        pendingUserInputs = dequeuePrompt(
                            it.pendingUserInputs, callId, userInputKey,
                        ),
                    )
                }
            }
            "pending-prompts" -> applyPendingPrompts(msg)
            "replay-gap" -> {
                // Contract (C): the relay's replay buffer had already evicted
                // part of what we asked for. Mark the hole so a truncated
                // transcript never reads as a complete one.
                val from = msg.long("missed_from") ?: 0L
                val to = msg.long("missed_to") ?: 0L
                appendEventOnce(
                    UiEvent.Gap(
                        id = "replay-gap-${msg.string("session_id").orEmpty()}-$from-$to",
                        missedFrom = from,
                        missedTo = to,
                    ),
                )
            }
            "pong" -> Unit
            "session-closed" -> _state.update {
                // Codex is gone; nothing can answer these any more.
                it.copy(
                    status = Status.Disconnected,
                    pending = false,
                    pendingApprovals = emptyList(),
                    pendingUserInputs = emptyList(),
                )
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
            "error" -> {
                if (msg["fatal"]?.jsonPrimitive?.contentOrNull == "true") {
                    // The relay will refuse this session id on every
                    // attempt (closed, gone, or not ours). Retrying is an
                    // endless reconnect loop; only a new session helps.
                    userClosed = true
                    reconnectJob?.cancel()
                }
                _state.update {
                    it.copy(
                        error = msg.string("error") ?: "relay error",
                        pending = false,
                    )
                }
            }
        }
    }

    private fun appendEventOnce(event: UiEvent) {
        _state.update { s ->
            if (s.events.any { it.id == event.id }) s else s.copy(events = s.events + event)
        }
    }

    /** Relay snapshot: authoritative about WHICH prompts are still open, so
     *  it prunes prompts a peer client answered without discarding the local
     *  ordering of the ones that remain. */
    private fun applyPendingPrompts(msg: JsonObject) {
        val snapshot = normalizePromptSnapshot(msg)
        _state.update {
            it.copy(
                pendingApprovals = reconcileQueue(
                    it.pendingApprovals, snapshot.approvals, approvalKey, approvalOrder,
                ),
                pendingUserInputs = reconcileQueue(
                    it.pendingUserInputs, snapshot.userInputs, userInputKey, userInputOrder,
                ),
            )
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
                            kind = data.string("kind") ?: it.session.kind,
                        ),
                    )
                }
            }

            "turn-started" -> {
                _state.update { it.copy(pending = true) }
            }

            "item-started" -> {
                val itemId = data.string("item_id") ?: return
                val itemType = data.string("item_type") ?: return
                val replayed = data["replayed"]?.let {
                    (it as? JsonPrimitive)?.contentOrNull == "true" || it.toString() == "true"
                } ?: false
                val next = buildUiEvent(data, itemId, itemType, replayed)
                appendEventOnce(next)
                if (itemType == "user_message" && !replayed) {
                    _state.update { it.copy(pending = true) }
                }
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
                                output = completedToolOutput(data, e.output),
                                status = data.string("status") ?: e.status,
                                durationMs = data.long("duration_ms") ?: e.durationMs,
                                error = data.string("error") ?: e.error,
                                rawResult = rawToolResult(data).ifBlank { e.rawResult },
                                completed = true,
                            )
                            else -> e
                        }
                    })
                }
            }

            // The relay drops every outstanding prompt for the session when a
            // turn ends, so both queues empty together.
            "turn-completed" -> _state.update {
                it.copy(
                    pending = false,
                    pendingApprovals = emptyList(),
                    pendingUserInputs = emptyList(),
                )
            }

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

            "goal-snapshot", "goal-updated" -> {
                _state.update { it.copy(goal = normalizeGoal(data["goal"])) }
            }

            "goal-cleared" -> {
                _state.update { it.copy(goal = null) }
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
                val prompt = parseApprovalPrompt(data) ?: return
                _state.update {
                    it.copy(
                        pendingApprovals = enqueuePrompt(
                            it.pendingApprovals, prompt, approvalKey,
                        ),
                    )
                }
            }

            "user-input-request" -> {
                val prompt = parseUserInputPrompt(data) ?: return
                _state.update {
                    it.copy(
                        pendingUserInputs = enqueuePrompt(
                            it.pendingUserInputs, prompt, userInputKey,
                        ),
                    )
                }
            }

            "slash-ack" -> {
                val cmd = data.string("command") ?: "?"
                val ok = (data["ok"] as? JsonPrimitive)?.contentOrNull == "true"
                    || data["ok"]?.toString() == "true"
                if (ok && cmd == "plan") _state.update { it.copy(planMode = true) }
                if (ok && cmd == "default") _state.update { it.copy(planMode = false) }
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

private fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

private fun JsonObject.obj(key: String): JsonObject? =
    (this[key] as? JsonElement) as? JsonObject

private fun normalizeGoal(raw: JsonElement?): ThreadGoal? {
    val goal = raw as? JsonObject ?: return null
    fun string(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> goal.string(key) }
    fun long(vararg keys: String): Long? =
        keys.firstNotNullOfOrNull { key -> goal.long(key) }
    val statusRaw = string("status").orEmpty()
    val status = when (statusRaw.replace("-", "").replace("_", "").lowercase()) {
        "budgetlimited" -> "budgetLimited"
        "active" -> "active"
        "paused" -> "paused"
        "complete" -> "complete"
        else -> statusRaw
    }
    return ThreadGoal(
        threadId = string("thread_id", "threadId").orEmpty(),
        objective = string("objective").orEmpty(),
        status = status,
        tokenBudget = long("token_budget", "tokenBudget"),
        tokensUsed = long("tokens_used", "tokensUsed") ?: 0L,
        timeUsedSeconds = long("time_used_seconds", "timeUsedSeconds") ?: 0L,
        createdAt = string("created_at", "createdAt"),
        updatedAt = string("updated_at", "updatedAt"),
    )
}

private fun buildUiEvent(
    data: JsonObject,
    itemId: String,
    itemType: String,
    replayed: Boolean,
): UiEvent = when (itemType) {
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
    "mcp_tool_call" -> UiEvent.Tool(
        id = itemId,
        tool = formatMcpTool(data),
        command = jsonText(data["arguments"]),
        output = formatMcpOutput(data),
        completed = replayed ||
            data.string("status") == "completed" ||
            data.string("status") == "failed",
        toolKind = "mcp",
        status = data.string("status") ?: "",
        durationMs = data.long("duration_ms"),
        error = data.string("error") ?: "",
        rawArguments = jsonText(data["arguments"]),
        rawResult = rawToolResult(data),
    )
    "dynamic_tool_call" -> UiEvent.Tool(
        id = itemId,
        tool = formatDynamicTool(data),
        command = jsonText(data["arguments"]),
        output = formatDynamicOutput(data),
        completed = replayed ||
            data.string("status") == "completed" ||
            data.string("status") == "failed",
        toolKind = "dynamic",
        status = data.string("status") ?: "",
        durationMs = data.long("duration_ms"),
        error = data.string("error") ?: "",
        rawArguments = jsonText(data["arguments"]),
        rawResult = rawToolResult(data),
    )
    "collab_agent_tool_call" -> UiEvent.Tool(
        id = itemId,
        tool = formatCollabTool(data.string("tool")),
        command = data.string("prompt") ?: "",
        output = formatCollabStatus(data),
        completed = replayed ||
            data.string("status") == "completed" ||
            data.string("status") == "failed",
    )
    "user_message" -> UiEvent.User(
        id = itemId,
        text = data.string("text") ?: "",
        imageCount = data["image_count"]?.jsonPrimitive?.contentOrNull
            ?.toIntOrNull() ?: 0,
    )
    else -> UiEvent.System(id = itemId, label = itemType, detail = "")
}

private fun formatCollabTool(tool: String?): String =
    when (tool) {
        "spawnAgent" -> "spawn agent"
        "sendInput" -> "send input"
        "resumeAgent" -> "resume agent"
        "wait" -> "wait agents"
        "closeAgent" -> "close agent"
        else -> tool ?: "subagent"
    }

private fun formatCollabStatus(data: JsonObject): String {
    val status = data.string("status") ?: "inProgress"
    val receivers = (data["receiver_thread_ids"] as? JsonArray)?.size ?: 0
    val model = data.string("model")?.let { " · $it" } ?: ""
    val receiverText = if (receivers > 0) {
        " · $receivers thread${if (receivers == 1) "" else "s"}"
    } else {
        ""
    }
    return status + model + receiverText
}

private fun formatMcpTool(data: JsonObject): String {
    val server = data.string("server")
    val tool = data.string("tool")
    val name = listOfNotNull(server, tool).joinToString(".").ifBlank { "tool" }
    return "MCP · $name"
}

private fun formatDynamicTool(data: JsonObject): String {
    val namespace = data.string("namespace")
    val tool = data.string("tool")
    val name = listOfNotNull(namespace, tool).joinToString(".").ifBlank { tool ?: "dynamic" }
    return "TOOL · $name"
}

private fun formatMcpOutput(data: JsonObject): String {
    val parts = mutableListOf(data.string("status") ?: "inProgress")
    data.long("duration_ms")?.let { parts += "${it}ms" }
    data.string("error")?.let { parts += "error: $it" }
    extractMcpResultText(data["result"]).takeIf { it.isNotBlank() }?.let { parts += it }
    return parts.joinToString("\n")
}

private fun formatDynamicOutput(data: JsonObject): String {
    val parts = mutableListOf(data.string("status") ?: "inProgress")
    data.long("duration_ms")?.let { parts += "${it}ms" }
    data["success"]?.jsonPrimitive?.contentOrNull?.let { parts += if (it == "true") "success" else "failed" }
    jsonText(data["content_items"]).takeIf { it.isNotBlank() }?.let { parts += it }
    return parts.joinToString("\n")
}

private fun completedToolOutput(data: JsonObject, fallback: String): String =
    when (data.string("item_type")) {
        "collab_agent_tool_call" -> formatCollabStatus(data)
        "mcp_tool_call" -> formatMcpOutput(data)
        "dynamic_tool_call" -> formatDynamicOutput(data)
        else -> data.string("output") ?: fallback
    }

private fun rawToolResult(data: JsonObject): String =
    when (data.string("item_type")) {
        "mcp_tool_call" -> jsonText(data["result"])
        "dynamic_tool_call" -> jsonText(data["content_items"])
        else -> ""
    }

private fun extractMcpResultText(raw: JsonElement?): String {
    val obj = raw as? JsonObject ?: return ""
    val content = obj["content"] as? JsonArray
    val texts = content
        ?.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> item.contentOrNull
                is JsonObject -> item.string("text")
                else -> null
            }
        }
        ?.filter { it.isNotBlank() }
        ?: emptyList()
    if (texts.isNotEmpty()) return texts.joinToString("\n")
    return jsonText(obj["structuredContent"])
}

private fun jsonText(raw: JsonElement?): String {
    if (raw == null) return ""
    return (raw as? JsonPrimitive)?.contentOrNull ?: raw.toString()
}
