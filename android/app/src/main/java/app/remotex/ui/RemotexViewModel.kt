package app.remotex.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import app.remotex.BuildConfig
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.remotex.model.FsEntry
import app.remotex.model.Host
import app.remotex.model.HostTelemetryData
import app.remotex.model.HostTelemetrySnapshot
import app.remotex.model.HostTelemetrySample
import app.remotex.model.ModelInfo
import app.remotex.model.TelemetryHistory
import app.remotex.model.ThreadInfo
import app.remotex.model.UiEvent
import app.remotex.persistence.ActiveSession
import app.remotex.persistence.ActiveSessionStore
import app.remotex.net.RelayClient
import app.remotex.net.RelayHttpException
import app.remotex.net.InventorySocket
import app.remotex.net.normalizeRelayBaseUrl
import app.remotex.net.SessionSocket
import app.remotex.net.SocketEvent
import app.remotex.net.outboundFrameFits
import app.remotex.security.SecureTokenStore
import app.remotex.security.TokenStore
import app.remotex.security.relayScopeKey
import app.remotex.service.RemotexEvents
import app.remotex.service.SessionForegroundService
import app.remotex.service.SessionNotifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import java.io.IOException
import kotlin.math.min
import kotlin.random.Random

enum class Screen { Hosts, Threads, Files, Session }

enum class Status { Idle, Opening, Connecting, Connected, Disconnected, Error }

internal fun hostConnectionErrorMessage(cause: Throwable): String = when {
    cause is RelayHttpException && cause.statusCode == 401 ->
        "That access token was not accepted."
    cause is RelayHttpException && cause.statusCode == 429 -> {
        val retryAfter = cause.retryAfter?.trim().orEmpty()
        when {
            retryAfter.all(Char::isDigit) && retryAfter.isNotEmpty() ->
                "Too many attempts. Try again in $retryAfter seconds."
            retryAfter.isNotEmpty() -> "Too many attempts. Try again after $retryAfter."
            else -> "Too many attempts. Try again shortly."
        }
    }
    cause is RelayHttpException && cause.statusCode >= 500 ->
        "The relay is unavailable right now. Try again shortly."
    cause is RelayHttpException ->
        "The relay rejected the request. Check its address and try again."
    cause is IOException ->
        "Could not reach this relay. Check its address and your connection."
    else -> "Could not load hosts. Try again."
}

internal fun attachedTurnInFlight(msg: JsonObject): Boolean? =
    msg["turn_in_flight"]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.toBooleanStrictOrNull()

internal fun prepareSessionReplayCursor(
    cursors: MutableMap<String, Long>,
    sessionId: String,
    replayFromStart: Boolean,
): Long {
    if (replayFromStart) cursors[sessionId] = 0L
    return cursors[sessionId] ?: 0L
}

internal fun attachedStatus(msg: JsonObject): Status =
    if ((msg["replay_from"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L) > 0L) {
        Status.Connected
    } else {
        Status.Connecting
    }

data class SessionInfo(
    val sessionId: String,
    val hostId: String,
    val threadId: String? = null,
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

data class QueuedTurn(
    val id: String,
    val clientMessageId: String,
    val text: String,
    val model: String,
    val effort: String,
    val permissions: PermissionsMode,
    val images: List<PendingImage> = emptyList(),
    val sending: Boolean = false,
) {
    val imageCount: Int get() = images.size
}

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
    val userToken: String = "",
    val hosts: List<Host> = emptyList(),
    val selectedHostId: String? = null,
    val loading: Boolean = false,
    val status: Status = Status.Idle,
    val session: SessionInfo? = null,
    val events: List<UiEvent> = emptyList(),
    // Tail-first history: the daemon ships only the last couple of turns;
    // scrolling to the top pages older ones in via `history-more`.
    val historyHasMore: Boolean = false,
    val historyOldest: Int = 0,
    val historyLoading: Boolean = false,
    // Bumped when a history TAIL commits, so the list jumps to the bottom
    // exactly once per replay instead of on every event change.
    val historyTailTick: Long = 0L,
    /** Events that arrived via history replay; they sit at the front of
     *  `events`, so this doubles as the "new messages" divider index. */
    val historyEventCount: Int = 0,
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
    val imagePreparing: Boolean = false,
    val queuedTurns: List<QueuedTurn> = emptyList(),
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

// Workspace uploads use the relay/daemon's 25 MiB HTTP limit.
const val MAX_FILE_BYTES = 25L * 1024 * 1024

// Images are base64 inside an OkHttp WebSocket frame. OkHttp's hard queue
// ceiling is 16 MiB, so 25 MiB raw (the old limit) disconnected the socket.
// Ten MiB raw leaves room for 4/3 base64 growth plus JSON and control frames.
const val MAX_IMAGE_ATTACHMENT_BYTES = 10L * 1024 * 1024

fun formatBytes(n: Long): String = when {
    n >= 1024 * 1024 -> "%.1f MB".format(n / (1024.0 * 1024.0))
    n >= 1024 -> "${n / 1024} KB"
    else -> "$n B"
}

/**
 * One entry in the model picker. Filled from the selected host's codex via
 * `GET /api/hosts/{id}/models` (→ codex `model/list`), which reports the
 * models that host offers and the reasoning efforts each one supports.
 *
 * No model names are hardcoded in this client. A shipped list goes stale
 * silently — this file used to name `gpt-5.5` as "newest frontier" while
 * hosts served `gpt-5.6-*`, and its effort list had no `max`/`ultra`, so
 * those were unselectable. See Issues.md I-002.
 */
data class ModelOption(
    val id: String,
    val label: String,
    val hint: String,
    val efforts: List<String>,
)

/**
 * The only option we can offer before a host answers: id "" means "send no
 * model override", so codex picks its own default. Replaced by the host's
 * real list as soon as [RemotexViewModel] fetches it.
 */
val MODEL_OPTIONS = listOf(
    ModelOption("", "default", "codex picks", ALL_EFFORTS),
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

internal fun applyResolvedSettings(state: UiState, settings: JsonObject?): UiState {
    if (settings == null) return state
    val permissions = settings.string("permissions")?.let { wire ->
        PermissionsMode.entries.firstOrNull { it.wire == wire }
    }
    return state.copy(
        model = settings.string("model")?.takeIf { it.isNotBlank() } ?: state.model,
        effort = settings.string("effort")?.takeIf { it.isNotBlank() } ?: state.effort,
        permissions = permissions ?: state.permissions,
    )
}

internal fun applySessionDetails(state: UiState, data: JsonObject): UiState {
    val base = state.copy(
        session = state.session?.copy(
            threadId = data.string("thread_id") ?: state.session.threadId,
            model = data.string("model") ?: state.session.model,
            cwd = data.string("cwd") ?: state.session.cwd,
            kind = data.string("kind") ?: state.session.kind,
        ),
    )
    val settings = data.obj("settings")
    val resolved = applyResolvedSettings(base, settings)
    if (!settings?.string("model").isNullOrBlank()) return resolved
    return data.string("model")?.takeIf { it.isNotBlank() }
        ?.let { resolved.copy(model = it) } ?: resolved
}

internal fun applyTurnCompletion(state: UiState, data: JsonObject): UiState = state.copy(
    pending = false,
    pendingApprovals = emptyList(),
    pendingUserInputs = emptyList(),
    error = data.string("error") ?: state.error,
)

internal fun applyFatalSessionError(state: UiState, error: String): UiState = state.copy(
    status = Status.Disconnected,
    pending = false,
    error = error,
    queuedTurns = emptyList(),
)

internal fun previewEventId(threadId: String, index: Int): String = "preview_${threadId}_$index"

internal fun reconcileSharedTurn(state: UiState, active: Boolean?): UiState = when (active) {
    true -> state.copy(pending = true)
    false -> state.copy(
        pending = false,
        pendingApprovals = emptyList(),
        pendingUserInputs = emptyList(),
    )
    null -> state
}

internal fun markQueuedTurnSending(queue: List<QueuedTurn>, id: String): List<QueuedTurn> =
    queue.map { if (it.id == id) it.copy(sending = true) else it }

internal fun resetSendingQueuedTurn(queue: List<QueuedTurn>): List<QueuedTurn> =
    queue.mapIndexed { index, turn ->
        if (index == 0 && turn.sending) turn.copy(sending = false) else turn
    }

internal fun acknowledgeQueuedTurn(
    queue: List<QueuedTurn>,
    clientMessageId: String,
): List<QueuedTurn> = queue.filterNot { it.clientMessageId == clientMessageId }

internal fun buildTurnStartFrame(turn: QueuedTurn): String = Json.encodeToString(
    JsonObject.serializer(),
    buildJsonObject {
        put("type", "turn-start")
        put("input", turn.text)
        put("client_message_id", turn.clientMessageId)
        if (turn.model.isNotEmpty()) put("model", turn.model)
        if (turn.effort.isNotEmpty() && turn.effort != "none") put("effort", turn.effort)
        put("permissions", turn.permissions.wire)
        if (turn.images.isNotEmpty()) {
            put("images", buildJsonArray {
                turn.images.forEach { image ->
                    addJsonObject {
                        put("mime", image.mime)
                        put("data", image.base64)
                    }
                }
            })
        }
    },
)

class RemotexViewModel internal constructor(
    application: Application,
    private val relayUrl: String,
    private val tokenStore: TokenStore = SecureTokenStore(application, relayScopeKey(relayUrl)),
    private val activeSessionStore: ActiveSessionStore = ActiveSessionStore(
        application,
        relayScopeKey(relayUrl),
    ),
) : AndroidViewModel(application) {
    private val client = RelayClient(
        baseUrl = relayUrl,
        allowInsecureHttp = BuildConfig.DEBUG,
    )
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
    // Non-null while a history batch streams in; replayed items collect
    // here and land as ONE state update on history-end / chunk-end.
    private var historyBuffer: MutableList<UiEvent>? = null
    private var historyBufferPrepend = false
    private var reconnectJob: Job? = null
    private var imagePrepareJob: Job? = null
    private var reconnectAttempt: Int = 0
    private var userClosed: Boolean = false
    private var telemetryJob: Job? = null
    private var telemetryHostId: String? = null
    private var modelFetchJob: Job? = null
    private var tokenClearJob: Job? = null
    private var openSessionJob: Job? = null
    private var openSessionGeneration: Long = 0L
    private var browseJob: Job? = null
    private val tokenStoreMutex = Mutex()
    private var activeSessionSaveJob: Job? = null
    private var tokenEdited = false
    private var inventorySocket: InventorySocket? = null
    private var inventorySocketJob: Job? = null
    private var inventoryReconnectJob: Job? = null
    private var inventoryHostRefreshJob: Job? = null
    private var inventoryThreadRefreshJob: Job? = null
    private var inventoryReconnectAttempt = 0
    private var inventoryClosed = true
    // Host whose model list is currently in state; keeps repeated host
    // selections from re-fetching the same list.
    private var modelOptionsHostId: String? = null
    private val clientId: String = "android-${UUID.randomUUID().toString().take(12)}"
    private val inventoryClientId: String = "inventory-$clientId"
    private val lastSeqBySession: MutableMap<String, Long> = mutableMapOf()
    private val sentImagesByMessageId: MutableMap<String, List<String>> = mutableMapOf()

    fun setToken(token: String) {
        tokenEdited = true
        stopInventory()
        telemetryJob?.cancel()
        telemetryJob = null
        telemetryHostId = null
        modelFetchJob?.cancel()
        modelFetchJob = null
        modelOptionsHostId = null
        _state.update {
            it.copy(
                userToken = token,
                hosts = emptyList(),
                selectedHostId = null,
                threads = emptyList(),
                hostTelemetry = emptyMap(),
                loading = false,
                error = null,
            )
        }
        tokenClearJob?.cancel()
        tokenClearJob = viewModelScope.launch { persistToken("") }
    }

    init {
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { tokenStore.load() }
            if (!tokenEdited && saved.isNotBlank()) {
                _state.update { it.copy(userToken = saved) }
                if (normalizeRelayBaseUrl(relayUrl, BuildConfig.DEBUG).isSuccess) {
                    val active = withContext(Dispatchers.IO) { activeSessionStore.load() }
                    if (active != null) restoreActiveSession(active)
                }
                refresh()
            }
        }
        // No host picked yet, so this can only reach the hostless default
        // list; selecting a host re-asks that host (see [refreshModelOptions]).
        if (normalizeRelayBaseUrl(relayUrl, BuildConfig.DEBUG).isSuccess) refreshModelOptions(null)
        observePendingForNotifications()
        observeNotificationActions()
    }

    private fun restoreActiveSession(active: ActiveSession) {
        userClosed = false
        _lastResumeThreadId = active.threadId
        lastSeqBySession[active.sessionId] = active.lastSeq
        _state.update {
            it.copy(
                screen = Screen.Session,
                selectedHostId = active.hostId,
                session = SessionInfo(
                    sessionId = active.sessionId,
                    hostId = active.hostId,
                    threadId = active.threadId,
                ),
                status = Status.Connecting,
                pending = false,
                error = null,
            )
        }
        onHostSelected(active.hostId)
        attachSocket(active.sessionId)
    }

    /** Throttle cursor writes during token-delta bursts. A slightly stale
     * cursor is safe (event ids are deduplicated); an absent cursor is not,
     * because it makes process recreation depend on the relay's full tail. */
    private fun persistActiveSessionSoon() {
        if (activeSessionSaveJob?.isActive == true) return
        activeSessionSaveJob = viewModelScope.launch {
            delay(250L)
            saveActiveSessionNow()
        }
    }

    private fun saveActiveSessionNow() {
        val session = _state.value.session ?: return
        activeSessionStore.save(
            ActiveSession(
                sessionId = session.sessionId,
                hostId = session.hostId,
                threadId = session.threadId ?: _lastResumeThreadId,
                lastSeq = lastSeqBySession[session.sessionId] ?: 0L,
            ),
        )
    }

    private fun clearActiveSession() {
        activeSessionSaveJob?.cancel()
        activeSessionSaveJob = null
        activeSessionStore.clear()
    }

    private suspend fun persistToken(token: String): Boolean = tokenStoreMutex.withLock {
        try {
            withContext(Dispatchers.IO) {
                if (token.isBlank()) tokenStore.clear() else tokenStore.save(token)
            }
            true
        } catch (cause: Throwable) {
            if (cause is CancellationException) throw cause
            _state.update { it.copy(error = "Could not save the access token securely.") }
            false
        }
    }

    /**
     * Model list, most-specific source first (contract B): what the selected
     * host's codex actually offers → the hostless /api/models response →
     * the embedded MODEL_OPTIONS already sitting in state. Every failure is
     * silent; the picker is never left empty.
     */
    private fun refreshModelOptions(hostId: String?) {
        modelFetchJob?.cancel()
        // Even a cached-host selection must cancel the previous host's slow
        // request, otherwise B can overwrite the picker after switching B→A.
        if (hostId != null && hostId == modelOptionsHostId) return
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
                        threadId = extractThreadId(s),
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
                            threadId = extractThreadId(s),
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
        val threadId = extractThreadId(s)
        val chatTitle = threadId?.let { tid ->
            s.threads.firstOrNull { it.id == tid }?.let { thread ->
                thread.title?.takeIf { it.isNotBlank() } ?: thread.preview.take(40)
            }
        } ?: "current chat"
        return chatTitle to hostNick
    }

    private fun extractThreadId(s: UiState): String? =
        s.session?.threadId ?: _lastResumeThreadId

    private var _lastResumeThreadId: String? = null

    fun setModel(model: String) {
        _state.update {
            val supported = effortsFor(model, it.modelOptions)
            val nextEffort = if (it.effort in supported) it.effort else EFFORT_DEFAULT
            it.copy(model = model, effort = nextEffort)
        }
    }

    /** Public slash sender — used by composer's plan-chip + autocomplete. */
    fun sendSlash(cmd: String, args: String = ""): Boolean {
        val sock = socket ?: run {
            _state.update { it.copy(error = "socket is not connected") }
            return false
        }
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
            return false
        }
        if (cmd == "plan") _state.update { it.copy(planMode = true) }
        if (cmd == "default") _state.update { it.copy(planMode = false) }
        return true
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
        if (decision !in pending.decisions) return
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
                    if (data != null) applyTelemetry(targetHost, data, snap.history)
                } catch (_: Throwable) {
                    // Transient failures are benign; next tick retries.
                }
                delay(3_000L)
            }
        }
    }

    private fun applyTelemetry(
        hostId: String,
        data: HostTelemetryData,
        relayHistory: List<HostTelemetrySample> = emptyList(),
    ) {
        _state.update { s ->
            val prev = s.hostTelemetry[hostId]
            val history = if (relayHistory.isNotEmpty()) {
                TelemetryHistory.fromRelay(relayHistory)
            } else {
                (prev?.history ?: TelemetryHistory()).push(data)
            }
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
        cancelPendingSessionOpen()
        browseJob?.cancel()
        browseJob = null
        _state.update {
            it.copy(
                screen = Screen.Threads,
                status = if (it.session == null) Status.Idle else it.status,
            )
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
        browseJob?.cancel()
        browseJob = viewModelScope.launch {
            // Keep the committed path and its entries paired until the new
            // listing succeeds. A failed navigation must not relabel stale
            // children as if they belonged to the requested directory.
            _state.update { it.copy(browseLoading = true, error = null) }
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
            } catch (cause: CancellationException) {
                throw cause
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
        if (_state.value.loading) return
        val relayError = normalizeRelayBaseUrl(relayUrl, BuildConfig.DEBUG).exceptionOrNull()
        if (relayError != null) {
            _state.update {
                it.copy(
                    loading = false,
                    hosts = emptyList(),
                    error = relayError.message ?: "Invalid relay URL.",
                )
            }
            return
        }
        val token = _state.value.userToken.trim()
        if (token.isEmpty()) {
            viewModelScope.launch { persistToken("") }
            _state.update {
                it.copy(
                    userToken = "",
                    hosts = emptyList(),
                    loading = false,
                    error = "Enter an access token.",
                )
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val hosts = client.listHosts(token)
                if (_state.value.userToken.trim() != token) return@launch
                tokenClearJob?.join()
                if (_state.value.userToken.trim() != token) return@launch
                if (!persistToken(token)) {
                    _state.update { it.copy(loading = false) }
                    return@launch
                }
                if (_state.value.userToken.trim() != token) return@launch
                tokenEdited = false
                // Persist and publish the same normalized credential. REST
                // already used the trimmed token; leaving the draft in state
                // made the inventory/session WebSockets authenticate with a
                // different value until the process restarted.
                _state.update { it.copy(userToken = token, hosts = hosts, loading = false) }
                ensureInventoryConnected()
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
                if (_state.value.userToken.trim() != token) return@launch
                if (t is RelayHttpException && t.statusCode == 401) {
                    persistToken("")
                    _state.update {
                        it.copy(
                            userToken = "",
                            hosts = emptyList(),
                            selectedHostId = null,
                            loading = false,
                            error = hostConnectionErrorMessage(t),
                        )
                    }
                } else {
                    _state.update {
                        it.copy(loading = false, error = hostConnectionErrorMessage(t))
                    }
                }
            }
        }
    }

    private fun ensureInventoryConnected() {
        if (relayUrl.isBlank() || _state.value.userToken.isBlank() || inventorySocket != null) return
        inventoryClosed = false
        connectInventory()
    }

    private fun connectInventory() {
        if (inventoryClosed || inventorySocket != null) return
        val sock = InventorySocket(
            baseUrl = relayUrl,
            userToken = _state.value.userToken,
            clientId = inventoryClientId,
            allowInsecureHttp = BuildConfig.DEBUG,
        )
        inventorySocket = sock
        inventorySocketJob = viewModelScope.launch {
            sock.events.collect { event ->
                if (inventorySocket !== sock) return@collect
                when (event) {
                    is SocketEvent.Frame -> handleInventoryFrame(event.text)
                    is SocketEvent.Closed -> inventoryDropped(sock)
                    is SocketEvent.Failure -> inventoryDropped(sock)
                }
            }
        }
    }

    private fun handleInventoryFrame(raw: String) {
        val frame = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        when (frame.string("type")) {
            "inventory-ready" -> {
                inventoryReconnectAttempt = 0
                scheduleInventoryHostRefresh()
                scheduleInventoryThreadRefresh(frame.string("host_id"))
            }
            "hosts-changed" -> {
                scheduleInventoryHostRefresh()
                val selected = _state.value.selectedHostId
                if (frame.string("reason") == "daemon-online" && frame.string("host_id") == selected) {
                    scheduleInventoryThreadRefresh(selected)
                }
            }
            "threads-changed" -> {
                val selected = _state.value.selectedHostId
                if (selected != null && (frame.string("host_id") == null || frame.string("host_id") == selected)) {
                    scheduleInventoryThreadRefresh(selected)
                }
            }
            "error" -> {
                val fatal = frame.bool("fatal") == true
                _state.update { it.copy(error = frame.string("error") ?: "inventory connection failed") }
                if (fatal) stopInventory()
            }
        }
    }

    private fun inventoryDropped(sock: InventorySocket) {
        if (inventorySocket !== sock) return
        inventorySocket = null
        inventorySocketJob = null
        if (inventoryClosed) return
        scheduleInventoryReconnect()
    }

    private fun scheduleInventoryReconnect() {
        if (inventoryClosed || inventoryReconnectJob?.isActive == true) return
        val delayMs = min(30_000L, 1_000L shl min(inventoryReconnectAttempt, 5))
        inventoryReconnectAttempt += 1
        inventoryReconnectJob = viewModelScope.launch {
            delay(delayMs)
            inventoryReconnectJob = null
            connectInventory()
        }
    }

    private fun scheduleInventoryHostRefresh() {
        inventoryHostRefreshJob?.cancel()
        inventoryHostRefreshJob = viewModelScope.launch {
            delay(100L)
            refresh()
        }
    }

    private fun scheduleInventoryThreadRefresh(hostId: String?) {
        val selected = _state.value.selectedHostId ?: return
        if (hostId != null && hostId != selected) return
        inventoryThreadRefreshJob?.cancel()
        inventoryThreadRefreshJob = viewModelScope.launch {
            delay(150L)
            if (_state.value.selectedHostId == selected) refreshThreads()
        }
    }

    fun reconnectInventoryNow() {
        if (inventoryClosed || inventorySocket != null) return
        inventoryReconnectJob?.cancel()
        inventoryReconnectJob = null
        inventoryReconnectAttempt = 0
        connectInventory()
    }

    private fun stopInventory() {
        inventoryClosed = true
        inventoryReconnectJob?.cancel()
        inventoryReconnectJob = null
        inventoryHostRefreshJob?.cancel()
        inventoryHostRefreshJob = null
        inventoryThreadRefreshJob?.cancel()
        inventoryThreadRefreshJob = null
        inventorySocketJob?.cancel()
        inventorySocketJob = null
        inventorySocket?.close()
        inventorySocket = null
        inventoryReconnectAttempt = 0
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

    private fun cancelPendingSessionOpen() {
        openSessionGeneration += 1L
        openSessionJob?.cancel()
        openSessionJob = null
    }

    fun openSession(
        resumeThreadId: String? = null,
        cwd: String? = null,
        hostId: String? = null,
    ) {
        val target = hostId ?: _state.value.selectedHostId ?: return
        cancelPendingSessionOpen()
        detachSession()
        val generation = openSessionGeneration
        userClosed = false
        // Track for the foreground service / done notification — they
        // need the thread id to look up the title and deep-link back.
        _lastResumeThreadId = resumeThreadId
        sentImagesByMessageId.clear()
        if (resumeThreadId != null) {
            paintPreview(target, resumeThreadId)
        }
        _state.update {
            it.copy(
                screen = Screen.Session,
                status = Status.Opening,
                pending = false,
                error = null,
                events = emptyList(),
                historyHasMore = false,
                historyOldest = 0,
                historyLoading = false,
                historyEventCount = 0,
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
                queuedTurns = emptyList(),
                resuming = false,
                resumingSinceMs = 0L,
                tokensInput = 0L,
                tokensOutput = 0L,
                tokensCached = 0L,
                tokensReasoning = 0L,
                goal = null,
            )
        }
        openSessionJob = viewModelScope.launch {
            val sid = try {
                client.openSession(
                    _state.value.userToken,
                    target,
                    resumeThreadId = resumeThreadId,
                    cwd = cwd,
                )
            } catch (cause: CancellationException) {
                throw cause
            } catch (t: Throwable) {
                if (generation != openSessionGeneration) return@launch
                _state.update {
                    it.copy(status = Status.Error, error = t.message ?: "open failed")
                }
                return@launch
            }
            if (generation != openSessionGeneration || _state.value.screen != Screen.Session) {
                return@launch
            }
            _state.update {
                it.copy(
                    session = SessionInfo(
                        sessionId = sid,
                        hostId = target,
                        threadId = resumeThreadId,
                    ),
                    status = Status.Connecting,
                )
            }
            lastSeqBySession[sid] = 0L
            saveActiveSessionNow()
            // State above deliberately cleared the transcript. A reused live
            // session id must therefore replay from zero; transport-only
            // reconnects keep their cursor in attachSocket's default path.
            attachSocket(sid, replayFromStart = true)
            if (generation == openSessionGeneration) openSessionJob = null
        }
    }

    private fun attachSocket(sid: String, replayFromStart: Boolean = false) {
        socket?.close()
        socketJob?.cancel()
        val sock = SessionSocket(
            relayUrl,
            _state.value.userToken,
            sid,
            clientId = clientId,
            lastSeq = prepareSessionReplayCursor(lastSeqBySession, sid, replayFromStart),
            allowInsecureHttp = BuildConfig.DEBUG,
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
        _state.update { it.copy(queuedTurns = resetSendingQueuedTurn(it.queuedTurns)) }
        if (userClosed) {
            _state.update { it.copy(status = Status.Disconnected, pending = false) }
            return
        }
        _state.update {
            it.copy(
                status = Status.Disconnected,
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

    fun sendTurn(text: String): Boolean {
        if (_state.value.imagePreparing) {
            _state.update { it.copy(error = "Wait for the image preview before sending.") }
            return false
        }
        val input = text.trim()
        val attachments = _state.value.pendingImages
        if (input.isEmpty() && attachments.isEmpty()) return false
        val sock = socket ?: return false
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
                    return false
                }
                if (cmd == "plan") _state.update { it.copy(planMode = true) }
                if (cmd == "default") _state.update { it.copy(planMode = false) }
                return true
            }
        }
        val turn = newTurn(input, attachments)
        rememberSentImages(turn)
        if (!sendTurnFrame(sock, buildTurnStartFrame(turn), attachments.isNotEmpty())) {
            forgetSentImages(turn.clientMessageId)
            return false
        }
        _state.update {
            it.copy(
                pending = true,
                pendingImages = emptyList(),
            )
        }
        return true
    }

    fun queueTurn(text: String): Boolean {
        val state = _state.value
        if (state.imagePreparing) {
            _state.update { it.copy(error = "Wait for the image preview before queueing.") }
            return false
        }
        val input = text.trim()
        val attachments = state.pendingImages
        if (!state.pending || (input.isEmpty() && attachments.isEmpty())) return false
        if (attachments.isEmpty() && input.startsWith("/")) return false
        val turn = newTurn(input, attachments).copy(
            id = "queued-${UUID.randomUUID().toString().take(8)}",
        )
        _state.update {
            it.copy(
                queuedTurns = it.queuedTurns + turn,
                pendingImages = emptyList(),
            )
        }
        return true
    }

    fun removeQueuedTurn(id: String) {
        _state.update { state ->
            state.copy(queuedTurns = state.queuedTurns.filterNot { it.id == id && !it.sending })
        }
    }

    private fun newTurn(input: String, attachments: List<PendingImage>): QueuedTurn {
        val state = _state.value
        return QueuedTurn(
            id = "turn-${UUID.randomUUID().toString().take(8)}",
            clientMessageId = "msg-${UUID.randomUUID().toString().take(8)}",
            text = input,
            model = state.model.trim(),
            effort = state.effort.trim(),
            permissions = state.permissions,
            images = attachments.toList(),
        )
    }

    private fun rememberSentImages(turn: QueuedTurn) {
        if (turn.images.isNotEmpty()) {
            sentImagesByMessageId[turn.clientMessageId] = turn.images.map { it.uri }
        }
    }

    private fun forgetSentImages(clientMessageId: String) {
        sentImagesByMessageId.remove(clientMessageId)
    }

    private fun sendTurnFrame(
        sock: SessionSocket,
        frame: String,
        hasImages: Boolean,
    ): Boolean {
        if (!outboundFrameFits(frame)) {
            _state.update {
                it.copy(
                    error = if (hasImages) {
                        "The attached images are too large to send. Remove one or choose a smaller image."
                    } else {
                        "This message is too large to send."
                    },
                )
            }
            return false
        }
        if (!sock.sendJson(frame)) {
            _state.update { it.copy(error = "Could not send; the session is reconnecting.") }
            return false
        }
        return true
    }

    private fun drainQueuedTurn() {
        val turn = _state.value.queuedTurns.firstOrNull() ?: return
        if (turn.sending) return
        val sock = socket ?: return
        rememberSentImages(turn)
        if (!sendTurnFrame(sock, buildTurnStartFrame(turn), turn.images.isNotEmpty())) {
            forgetSentImages(turn.clientMessageId)
            return
        }
        _state.update {
            it.copy(
                queuedTurns = markQueuedTurnSending(it.queuedTurns, turn.id),
                pending = true,
            )
        }
    }

    /**
     * Send a message into the turn that's already running (codex
     * `turn/steer`) instead of interrupting and retyping. The relay rejects
     * the frame when no turn is in flight, and echoes it to every attached
     * client — so we don't append it locally.
     */
    /**
     * Instant paint on resume: the disk-backed preview endpoint answers in
     * milliseconds (daemon LRU, no codex), so the last exchange is visible
     * while the real session opens. HISTORY commits replace these rows.
     */
    private fun paintPreview(hostId: String, threadId: String) {
        viewModelScope.launch {
            val preview = runCatching {
                client.getThreadPreview(_state.value.userToken, hostId, threadId)
            }.getOrNull() ?: return@launch
            if (!preview.available || preview.turns.isEmpty()) return@launch
            _state.update { s ->
                // Only while this exact resume is still opening with nothing
                // real on screen yet.
                if (s.screen != Screen.Session || s.events.isNotEmpty()) return@update s
                s.copy(events = preview.turns.mapIndexed { i, turn ->
                    if (turn.role == "user") {
                        UiEvent.User(id = previewEventId(threadId, i), text = turn.text)
                    } else {
                        UiEvent.Agent(id = previewEventId(threadId, i), text = turn.text, completed = true)
                    }
                })
            }
        }
    }

    /** Pull the next page of older turns (scroll-to-top backfill). */
    fun loadOlderHistory() {
        val s = _state.value
        if (!s.historyHasMore || s.historyLoading) return
        val sock = socket ?: return
        val frame = Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("type", "history-more")
                put("before", s.historyOldest)
                put("limit", 10)
            },
        )
        if (sock.sendJson(frame)) {
            _state.update { it.copy(historyLoading = true) }
        }
    }

    fun steerTurn(text: String): Boolean {
        if (_state.value.imagePreparing) {
            _state.update { it.copy(error = "Wait for the image preview before sending.") }
            return false
        }
        val input = text.trim()
        val attachments = _state.value.pendingImages
        if (input.isEmpty() && attachments.isEmpty()) return false
        if (!_state.value.pending) return false
        val sock = socket ?: return false
        val clientMessageId = "msg-${UUID.randomUUID().toString().take(8)}"
        val frame = Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("type", "turn-steer")
                put("input", input)
                put("client_message_id", clientMessageId)
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
        if (attachments.isNotEmpty()) {
            sentImagesByMessageId[clientMessageId] = attachments.map { it.uri }
        }
        if (!sendTurnFrame(sock, frame, attachments.isNotEmpty())) {
            forgetSentImages(clientMessageId)
            return false
        }
        _state.update { it.copy(pendingImages = emptyList()) }
        return true
    }

    /** Called from the UI when the user picks an image. Handles reading +
     *  base64-encoding off the main thread. */
    fun setImagePickerActive(active: Boolean) {
        if (!active && imagePrepareJob?.isActive == true) return
        _state.update { it.copy(imagePreparing = active, error = if (active) null else it.error) }
    }

    @androidx.annotation.VisibleForTesting
    internal fun replacePendingImagesForTest(images: List<PendingImage>) {
        _state.update { it.copy(pendingImages = images) }
    }

    fun attachImage(uri: Uri) {
        if (imagePrepareJob?.isActive == true) return
        _state.update { it.copy(imagePreparing = true, error = null) }
        val app = getApplication<Application>()
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val already = _state.value.pendingImages.sumOf { it.bytes }
                val remaining = (MAX_IMAGE_ATTACHMENT_BYTES - already).coerceAtLeast(0L)
                if (remaining == 0L) {
                    throw ContentTooLargeException(already, MAX_IMAGE_ATTACHMENT_BYTES)
                }
                val (normalized, label, encoded) = withContext(Dispatchers.IO) {
                    val metadata = app.contentResolver.openableMetadata(uri)
                    metadata.size?.let { knownSize ->
                        if (knownSize > MAX_FILE_BYTES) {
                            throw ContentTooLargeException(knownSize, MAX_FILE_BYTES)
                        }
                    }
                    val source = app.contentResolver.openInputStream(uri)?.use {
                        readBounded(it, MAX_FILE_BYTES)
                    }
                        ?: throw IllegalStateException("empty stream for $uri")
                    val image = normalizeImageAttachment(source, remaining)
                    Triple(
                        image,
                        metadata.displayName
                            ?: uri.lastPathSegment?.substringAfterLast('/')
                            ?: "image",
                        android.util.Base64.encodeToString(
                            image.bytes,
                            android.util.Base64.NO_WRAP,
                        ),
                    )
                }
                val currentBytes = _state.value.pendingImages.sumOf { it.bytes }
                if (currentBytes + normalized.bytes.size > MAX_IMAGE_ATTACHMENT_BYTES) {
                    _state.update {
                        it.copy(
                            error = "image: ${formatBytes(normalized.bytes.size.toLong())} exceeds the " +
                                "${formatBytes(MAX_IMAGE_ATTACHMENT_BYTES)} attachment limit",
                        )
                    }
                    return@launch
                }
                _state.update {
                    it.copy(
                        pendingImages = it.pendingImages + PendingImage(
                            uri = uri.toString(),
                            mime = normalized.mime,
                            base64 = encoded,
                            label = label.take(32),
                            bytes = normalized.bytes.size.toLong(),
                        ),
                        error = null,
                    )
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (t: Throwable) {
                _state.update { it.copy(error = "image: ${t.message ?: "read failed"}") }
            } finally {
                if (imagePrepareJob === kotlinx.coroutines.currentCoroutineContext()[Job]) {
                    _state.update { it.copy(imagePreparing = false) }
                    imagePrepareJob = null
                }
            }
        }
        imagePrepareJob = job
        job.start()
    }

    fun removeImage(index: Int) {
        _state.update { s ->
            if (index !in s.pendingImages.indices) s
            else s.copy(pendingImages = s.pendingImages.toMutableList().apply { removeAt(index) })
        }
    }

    fun closeSession() {
        cancelPendingSessionOpen()
        cancelImagePreparation()
        userClosed = true
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempt = 0
        socket?.close(endSession = true)
        socket = null
        socketJob?.cancel()
        socketJob = null
        sentImagesByMessageId.clear()
        clearActiveSession()
        _state.update {
            it.copy(
                status = Status.Idle,
                pending = false,
                planMode = false,
                pendingApprovals = emptyList(),
                pendingUserInputs = emptyList(),
                queuedTurns = emptyList(),
                slashFeedback = null,
                resuming = false,
                resumingSinceMs = 0L,
                goal = null,
            )
        }
    }

    private fun detachSession() {
        cancelImagePreparation()
        userClosed = true
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempt = 0
        socket?.close(endSession = false)
        socket = null
        socketJob?.cancel()
        socketJob = null
        sentImagesByMessageId.clear()
        clearActiveSession()
        _state.update { it.copy(queuedTurns = emptyList()) }
    }

    private fun cancelImagePreparation() {
        imagePrepareJob?.cancel()
        imagePrepareJob = null
        _state.update { it.copy(imagePreparing = false, pendingImages = emptyList()) }
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
            persistActiveSessionSoon()
        }
        when (msg.string("type")) {
            "attached" -> {
                reconnectAttempt = 0
                val turnInFlight = attachedTurnInFlight(msg)
                _state.update {
                    it.copy(
                        status = attachedStatus(msg),
                        error = null,
                        // Older relays omit the field; preserve local state
                        // until a turn event supplies an authoritative value.
                        pending = turnInFlight ?: it.pending,
                    )
                }
                if (turnInFlight == false) drainQueuedTurn()
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
            "session-closed" -> {
                sentImagesByMessageId.clear()
                clearActiveSession()
                _state.update {
                    // Codex is gone; nothing can answer these any more.
                    it.copy(
                        status = Status.Disconnected,
                        pending = false,
                        pendingApprovals = emptyList(),
                        pendingUserInputs = emptyList(),
                        queuedTurns = emptyList(),
                    )
                }
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
                val fatal = msg["fatal"]?.jsonPrimitive?.contentOrNull == "true"
                _state.update { it.copy(queuedTurns = resetSendingQueuedTurn(it.queuedTurns)) }
                if (fatal) {
                    // The relay will refuse this session id on every
                    // attempt (closed, gone, or not ours). Retrying is an
                    // endless reconnect loop; only a new session helps.
                    userClosed = true
                    reconnectJob?.cancel()
                    sentImagesByMessageId.clear()
                    clearActiveSession()
                }
                _state.update {
                    if (fatal) {
                        applyFatalSessionError(it, msg.string("error") ?: "relay error")
                    } else {
                        it.copy(error = msg.string("error") ?: "relay error")
                    }
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
                val threadId = data.string("thread_id")
                val sharedTurn = data.bool("shared_turn_in_flight")
                if (!threadId.isNullOrBlank()) _lastResumeThreadId = threadId
                _state.update { state ->
                    val base = state.copy(
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
                    )
                    reconcileSharedTurn(applySessionDetails(base, data), sharedTurn)
                }
                persistActiveSessionSoon()
                if (sharedTurn == false) drainQueuedTurn()
            }

            "turn-started" -> {
                _state.update { it.copy(pending = true) }
            }

            "item-started" -> {
                val itemId = data.string("item_id") ?: return
                val itemType = data.string("item_type") ?: return
                val buf = historyBuffer
                if (buf != null && data["replayed"] != null) {
                    buildUiEvent(data, itemId, itemType, replayed = true)
                        .let { buf.add(it) }
                    return
                }
                val replayed = data["replayed"]?.let {
                    (it as? JsonPrimitive)?.contentOrNull == "true" || it.toString() == "true"
                } ?: false
                val queuedMatch = itemType == "user_message" &&
                    _state.value.queuedTurns.any { it.clientMessageId == itemId }
                val imageUris = if (itemType == "user_message") {
                    sentImagesByMessageId.remove(itemId).orEmpty()
                } else {
                    emptyList()
                }
                val built = buildUiEvent(data, itemId, itemType, replayed)
                val next = if (built is UiEvent.User && imageUris.isNotEmpty()) {
                    built.copy(imageUris = imageUris)
                } else {
                    built
                }
                appendEventOnce(next)
                if (itemType == "user_message" && (!replayed || queuedMatch)) {
                    _state.update {
                        it.copy(
                            pending = true,
                            queuedTurns = acknowledgeQueuedTurn(it.queuedTurns, itemId),
                        )
                    }
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

            "item-patch" -> {
                // Progressive file-edit update: codex resends the whole patch
                // each time, so this replaces the diff instead of appending.
                val itemId = data.string("item_id") ?: return
                val output = data.string("output") ?: ""
                val command = data.obj("args")?.string("command") ?: ""
                _state.update { s ->
                    s.copy(events = s.events.map { e ->
                        if (e.id != itemId || e !is UiEvent.Tool) {
                            e
                        } else {
                            e.copy(
                                output = output,
                                command = command.ifEmpty { e.command },
                            )
                        }
                    })
                }
            }

            "steer-failed" -> {
                // The turn is still running; surface the error without ending it.
                _state.update {
                    it.copy(error = data.string("error") ?: "could not steer this turn")
                }
            }

            "item-completed" -> {
                // Replayed completions mirror their item-started payloads
                // exactly — nothing to patch while buffering.
                if (historyBuffer != null && data["replayed"] != null) return
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
            "turn-completed" -> {
                _state.update { applyTurnCompletion(it, data) }
                drainQueuedTurn()
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
                        val threadId = data.string("thread_id")
                        if (!threadId.isNullOrBlank()) _lastResumeThreadId = threadId
                        val sharedTurn = data.bool("shared_turn_in_flight")
                        _state.update { state ->
                            val base = state.copy(
                                status = Status.Connected,
                                error = null,
                                resuming = false,
                                resumingSinceMs = 0L,
                            )
                            reconcileSharedTurn(applySessionDetails(base, data), sharedTurn)
                        }
                        persistActiveSessionSoon()
                        if (sharedTurn == false) drainQueuedTurn()
                    }
                    "resume-failed" -> {
                        clearActiveSession()
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

            "history-begin" -> {
                historyBuffer = mutableListOf()
                historyBufferPrepend = false
            }

            "history-chunk-begin" -> {
                historyBuffer = mutableListOf()
                historyBufferPrepend = true
            }

            "history-end", "history-chunk-end" -> {
                val incoming = historyBuffer ?: mutableListOf()
                val prepend = historyBufferPrepend
                historyBuffer = null
                val oldest = (data["oldest"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
                val hasMore = (data["has_more"] as? JsonPrimitive)?.contentOrNull == "true"
                _state.update { s ->
                    // The authoritative tail replaces the instant-paint
                    // preview rows.
                    val kept = s.events.filterNot { it.id.startsWith("preview_") }
                    val seen = kept.mapTo(HashSet()) { it.id }
                    val fresh = incoming.filter { it.id !in seen }
                    s.copy(
                        // Prepending before existing events is right for both
                        // cases — on the initial tail, `events` holds at most
                        // live frames that raced in.
                        events = fresh + kept,
                        historyOldest = oldest,
                        historyHasMore = hasMore,
                        historyLoading = false,
                        historyTailTick = if (prepend) s.historyTailTick else s.historyTailTick + 1,
                        historyEventCount = s.historyEventCount
                            .coerceAtMost(kept.size) + fresh.size,
                    )
                }
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

            "session-settings" -> {
                _state.update { applyResolvedSettings(it, data) }
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
        stopInventory()
        telemetryJob?.cancel()
        telemetryJob = null
        super.onCleared()
    }

    /** Called before switching relay origins. ViewModels are keyed by URL and
     * remain in the Activity's store, so explicitly release the old origin's
     * sockets instead of leaving them alive until the Activity is destroyed. */
    fun releaseForRelayChange() {
        closeSession()
        stopInventory()
        telemetryJob?.cancel()
        telemetryJob = null
        modelFetchJob?.cancel()
        modelFetchJob = null
    }

    companion object {
        /**
         * [activeSessionScope] isolates the persisted "active session"
         * record. The tablet split pane runs a second view model against
         * the same relay; without its own scope it would clobber the
         * primary chat's process-restore state. Token storage is always
         * shared — both panes speak for the same user.
         */
        fun factory(
            application: Application,
            relayUrl: String,
            activeSessionScope: String? = null,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    RemotexViewModel(
                        application,
                        relayUrl,
                        activeSessionStore = ActiveSessionStore(
                            application,
                            activeSessionScope ?: relayScopeKey(relayUrl),
                        ),
                    ) as T
            }
    }
}

// Tiny helpers so the dispatch code above doesn't drown in ?.jsonObject?.get(...) chains.
private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

private fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

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
    // Codex has item types we don't render specially — contextCompaction,
    // webSearch, plan, sleep, subAgentActivity, enteredReviewMode… Show a
    // readable name instead of a raw camelCase identifier.
    else -> UiEvent.System(id = itemId, label = humanizeItemType(itemType), detail = "")
}

/** "contextCompaction" / "file_change" → "context compaction" / "file change". */
internal fun humanizeItemType(type: String): String =
    if (type.isEmpty()) {
        "item"
    } else {
        type.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
            .replace('_', ' ')
            .lowercase()
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
