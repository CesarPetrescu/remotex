import Foundation
import Combine
import UserNotifications

@MainActor
final class RemotexViewModel: ObservableObject {
    @Published var relayURL: String {
        didSet {
            UserDefaults.standard.set(relayURL, forKey: Self.relayURLKey)
            guard relayURL != oldValue else { return }
            configurationGeneration += 1
            clearConfigurationDependentState()
            if Self.normalizedRelayScope(relayURL) != Self.normalizedRelayScope(oldValue) {
                userToken = Self.loadUserToken(for: relayURL)
            }
        }
    }
    @Published var userToken: String {
        didSet {
            if userToken != oldValue {
                configurationGeneration += 1
                clearConfigurationDependentState()
            }
        }
    }
    @Published private(set) var hosts: [Host] = []
    @Published private(set) var selectedHost: Host?
    // Saved chats + picker options for the first online host — fetched
    // right after the host list so the recent list is tappable instantly.
    @Published private(set) var threads: [ThreadInfo] = []
    @Published private(set) var threadsHost: Host?
    @Published private(set) var modelOptions: [ModelOption] = []
    @Published var model: String = ""
    @Published var effort: String = ""
    @Published var permissions: String = "default"
    @Published private(set) var status: ConnectionStatus = .idle
    @Published private(set) var connectionMessage: String?
    @Published private(set) var resuming = false
    @Published private(set) var historyOnly = false
    @Published private(set) var session: SessionInfo?
    @Published private(set) var stream: [StreamItem] = []
    // Contract (F): ordered queues, not slots. The head is what the UI
    // renders; answering it pops the head and reveals the next, so a
    // second concurrent prompt never hides an unanswered one.
    @Published private(set) var pendingApprovals: [ApprovalPrompt] = []
    @Published private(set) var pendingUserInputs: [UserInputPrompt] = []
    @Published var prompt: String = ""
    @Published var errorMessage: String?
    @Published private(set) var pending: Bool = false
    @Published private(set) var pendingImages: [PendingImage] = []
    @Published private(set) var queuedTurns: [QueuedTurn] = []
    @Published private(set) var planMode = false
    @Published private(set) var goal: ThreadGoal?
    @Published private(set) var collaborationModes: [String] = []
    @Published private(set) var tokensInput = 0
    @Published private(set) var tokensOutput = 0
    @Published private(set) var tokensCached = 0
    @Published private(set) var tokensReasoning = 0
    // Tail-first history: the daemon ships only the last couple of turns;
    // reaching the top of the transcript pages older ones in.
    @Published private(set) var historyHasMore: Bool = false
    @Published private(set) var historyLoading: Bool = false
    // Bumped once per committed tail → the view jumps to the newest turn.
    @Published private(set) var historyTailTick: Int = 0
    // Bumped per committed older page; `historyAnchorId` is the row that
    // was at the top before the prepend, so the view can restore it.
    @Published private(set) var historyChunkTick: Int = 0
    @Published private(set) var historyAnchorId: String?
    // Host telemetry: latest snapshot + a small client-side ring for the
    // sparklines (the relay serves point-in-time samples).
    @Published private(set) var telemetry: HostTelemetryData?
    @Published private(set) var telemetryCpu: [Double] = []
    @Published private(set) var telemetryMem: [Double] = []
    @Published private(set) var telemetryGpu: [Double] = []
    @Published private(set) var workspaceEntries: [FsEntry] = []
    @Published private(set) var workspacePath: String = ""
    @Published private(set) var workspaceLoading = false
    @Published private(set) var startEntries: [FsEntry] = []
    @Published private(set) var startPath: String = "/"
    @Published private(set) var startLoading = false

    var hasPendingPrompts: Bool {
        !pendingApprovals.isEmpty || !pendingUserInputs.isEmpty
    }

    var isConfigured: Bool {
        RelayClient.validatedBaseComponents(baseURL: relayURL) != nil
            && !userToken.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var hasComposerContent: Bool {
        !prompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            || !pendingImages.isEmpty
    }

    private static let relayURLKey = "remotex.relayURL"
    private static let legacyUserTokenKey = "remotex.userToken"
    private static let tokenAccountPrefix = "remotex.userToken.relay."
    private static let activeSessionPrefix = "remotex.activeSession."
    private static let notificationPermissionKey = "remotex.notificationPermissionRequested"
    nonisolated static let maxAttachmentBytes = 25 * 1024 * 1024
    // The relay rejects a turn-start while a turn is still running.
    private static let turnBusyError = "a turn is already running in this chat"

    private let client = RelayClient()
    private var socket: SessionSocket?
    private var inventorySocket: InventorySocket?
    private var historyOldest = 0
    // Non-null while a history batch streams in; replayed items collect
    // here and land as one stream mutation on history-end / chunk-end.
    private var historyBuffer: [StreamItem]?
    private var historyBufferPrepend = false
    private var telemetryTask: Task<Void, Never>?
    private var configurationGeneration = 0
    private var sessionGeneration = 0
    private var sessionRelayScope: String?
    private var restoringPersistedSession = false
    private var appIsActive = true
    // Kept until the relay echoes our user message back, so a rejected
    // turn-start can hand the text back to the composer.
    private var unsentInput: String?
    private var unsentMessageId: String?
    // A relay echo proves a steer reached the daemon, not that codex accepted
    // it. Keep the text until completion so steer-failed can restore it.
    private var steerRecoveryInput: String?
    private var unsentImages: [PendingImage] = []
    private var steerRecoveryImages: [PendingImage] = []
    private var steerRecoveryMessageId: String?
    private var sentImagesByMessageId: [String: [PendingImage]] = [:]

    init() {
        let savedRelayURL = UserDefaults.standard.string(forKey: Self.relayURLKey) ?? ""
        self.relayURL = savedRelayURL
        self.userToken = Self.loadUserToken(for: savedRelayURL)
        restartInventorySocketNow()
        restorePersistedSessionIfPossible()
    }

    /// Canonical relay base used only to scope its bearer token. Reverse-proxy
    /// path prefixes remain distinct; query, fragments, default ports, case,
    /// and trailing slashes do not. Embedded credentials make the URL invalid.
    static func normalizedRelayScope(_ raw: String) -> String? {
        RelayClient.canonicalRelayScope(raw)
    }

    static func tokenAccount(for relayURL: String) -> String? {
        normalizedRelayScope(relayURL).map { tokenAccountPrefix + $0 }
    }

    static func activeSessionKey(for relayURL: String) -> String? {
        normalizedRelayScope(relayURL).map {
            activeSessionPrefix + Data($0.utf8).base64EncodedString()
        }
    }

    // Move the old plaintext/default account exactly once, but only after a
    // relay exists to receive it. An unscoped token is never sent anywhere.
    private static func loadUserToken(for relayURL: String) -> String {
        let plaintext = UserDefaults.standard.string(forKey: legacyUserTokenKey)
        if let plaintext, !plaintext.isEmpty,
           Keychain.string(for: legacyUserTokenKey) == nil {
            Keychain.set(plaintext, for: legacyUserTokenKey)
        }
        if plaintext != nil {
            UserDefaults.standard.removeObject(forKey: legacyUserTokenKey)
        }
        guard let account = tokenAccount(for: relayURL) else { return "" }
        if let scoped = Keychain.string(for: account) {
            // Re-saving upgrades older entries to the current device-only
            // accessibility class without changing their value.
            Keychain.set(scoped, for: account)
            Keychain.remove(account: legacyUserTokenKey)
            return scoped
        }
        guard let legacy = Keychain.string(for: legacyUserTokenKey), !legacy.isEmpty else {
            return ""
        }
        Keychain.set(legacy, for: account)
        Keychain.remove(account: legacyUserTokenKey)
        return legacy
    }

    private func restartInventorySocketNow() {
        inventorySocket?.close()
        inventorySocket = nil
        guard isConfigured else { return }
        let generation = configurationGeneration
        do {
            inventorySocket = try InventorySocket(
                baseURL: relayURL,
                userToken: userToken,
                onFrame: { [weak self] frame in
                    guard let self, generation == self.configurationGeneration else { return }
                    self.handleInventory(frame)
                },
                onState: { [weak self] state in
                    guard let self, generation == self.configurationGeneration else { return }
                    if case let .fatal(reason) = state {
                        self.inventorySocket = nil
                        self.errorMessage = reason
                    }
                }
            )
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func handleInventory(_ frame: [String: Any]) {
        switch frame.string("type") {
        case "inventory-ready":
            refreshHosts()
            if let host = threadsHost { refreshThreads(host) }
        case "hosts-changed":
            refreshHosts()
        case "threads-changed":
            guard let host = threadsHost,
                  frame.string("host_id").map({ $0 == host.id }) != false else { return }
            refreshThreads(host)
        case "error":
            errorMessage = frame.string("error") ?? "Inventory connection failed."
        default:
            break
        }
    }

    func refreshHosts() {
        guard isConfigured else {
            status = .idle
            errorMessage = "Enter a relay URL and user token first."
            return
        }
        let hasSession = session != nil
        if !hasSession { status = .loading }
        errorMessage = nil
        let generation = configurationGeneration
        let baseURL = relayURL
        let token = userToken
        Task {
            do {
                let loaded = try await client.listHosts(baseURL: baseURL, userToken: token)
                guard generation == configurationGeneration else { return }
                hosts = loaded
                if let account = Self.tokenAccount(for: baseURL) {
                    Keychain.set(token, for: account)
                }
                if inventorySocket == nil {
                    restartInventorySocketNow()
                }
                if !hasSession { status = .idle }
                let preferredId = selectedHost?.id ?? threadsHost?.id ?? session?.hostId
                let host = preferredId.flatMap { id in
                    hosts.first(where: { $0.id == id && $0.online })
                } ?? hosts.first(where: { $0.online })
                if let host {
                    refreshHostExtras(host)
                }
            } catch {
                guard generation == configurationGeneration else { return }
                if !hasSession { status = .error }
                errorMessage = error.localizedDescription
            }
        }
    }

    func signOut() {
        closeSession()
        if let account = Self.tokenAccount(for: relayURL) {
            Keychain.remove(account: account)
        }
        userToken = ""
        hosts = []
        threads = []
        threadsHost = nil
        modelOptions = []
        errorMessage = nil
        connectionMessage = nil
        status = .idle
    }

    /// Saved chats + model picker options for a host. Failures are silent —
    /// the host list is already on screen and both have safe fallbacks.
    func refreshHostExtras(_ host: Host) {
        if threadsHost?.id != host.id {
            threads = []
            modelOptions = []
            model = ""
            effort = ""
        }
        threadsHost = host
        refreshThreads(host)
        refreshModels(host)
    }

    private func refreshThreads(_ host: Host) {
        let generation = configurationGeneration
        let baseURL = relayURL
        let token = userToken
        Task {
            let loaded = (try? await client.listThreads(
                baseURL: baseURL, userToken: token, hostId: host.id
            )) ?? []
            guard generation == configurationGeneration,
                  threadsHost?.id == host.id else { return }
            threads = loaded
        }
    }

    private func refreshModels(_ host: Host) {
        let generation = configurationGeneration
        let baseURL = relayURL
        let token = userToken
        Task {
            let loaded = (try? await client.listHostModels(
                baseURL: baseURL, userToken: token, hostId: host.id
            )) ?? []
            guard generation == configurationGeneration,
                  threadsHost?.id == host.id else { return }
            modelOptions = loaded
        }
    }

    func prepareStart(on host: Host) {
        guard isConfigured, host.online else { return }
        selectedHost = host
        refreshHostExtras(host)
        startEntries = []
        startPath = "/"
        loadStartDirectory(on: host, path: "/")
    }

    func loadStartDirectory(on host: Host, path: String) {
        let target = path.trimmingCharacters(in: .whitespacesAndNewlines).ifEmpty("/")
        let generation = configurationGeneration
        let baseURL = relayURL
        let token = userToken
        startLoading = true
        Task {
            do {
                let listing = try await client.readDirectory(
                    baseURL: baseURL,
                    userToken: token,
                    hostId: host.id,
                    path: target
                )
                guard generation == configurationGeneration,
                      selectedHost?.id == host.id else { return }
                startPath = listing.path ?? target
                startEntries = Self.sortedEntries(listing.entries)
                startLoading = false
            } catch {
                guard generation == configurationGeneration,
                      selectedHost?.id == host.id else { return }
                startLoading = false
                errorMessage = error.localizedDescription
            }
        }
    }

    /// Resume a saved chat with instant paint: the disk-backed preview is
    /// fetched in parallel with the session open and shown as placeholder
    /// rows until the authoritative tail replaces them.
    func resumeThread(_ thread: ThreadInfo) {
        guard isConfigured, let host = threadsHost else {
            errorMessage = "Enter a relay URL and user token first."
            return
        }
        let baseURL = relayURL
        let token = userToken
        let previewTask = Task { [client] in
            try? await client.threadPreview(
                baseURL: baseURL, userToken: token, hostId: host.id, threadId: thread.id
            )
        }
        openSession(hostId: host.id, host: host, threadId: thread.id, cwd: thread.cwd)
        Task { @MainActor in
            guard let preview = await previewTask.value, preview.available else { return }
            guard stream.isEmpty, session?.threadId == thread.id else { return }
            stream = preview.turns.enumerated().map { index, turn in
                StreamItem(
                    id: "preview_\(thread.id)_\(index)",
                    role: turn.role == "user" ? .user : .agent,
                    title: turn.role == "user" ? "You" : "Codex",
                    text: turn.text,
                    completed: true
                )
            }
        }
    }

    func openSession(host: Host, cwd: String? = nil) {
        guard isConfigured else {
            errorMessage = "Enter a relay URL and user token first."
            return
        }
        guard host.online else {
            errorMessage = "\(host.nickname) is offline"
            return
        }
        openSession(hostId: host.id, host: host, cwd: cwd)
    }

    private func openSession(
        hostId: String,
        host: Host?,
        threadId: String? = nil,
        cwd: String? = nil
    ) {
        closeSession(clearSelectedHost: false)
        sessionGeneration += 1
        let generation = sessionGeneration
        let configuration = configurationGeneration
        let baseURL = relayURL
        let token = userToken
        selectedHost = host
        status = .opening
        errorMessage = nil
        connectionMessage = nil
        resuming = threadId != nil
        historyOnly = false
        stream = []
        resetHistoryPaging()
        sessionRelayScope = Self.normalizedRelayScope(baseURL)
        restoringPersistedSession = false

        Task {
            do {
                let sessionId = try await client.openSession(
                    baseURL: baseURL,
                    userToken: token,
                    hostId: hostId,
                    threadId: threadId,
                    cwd: cwd
                )
                guard generation == sessionGeneration,
                      configuration == configurationGeneration else { return }
                session = SessionInfo(sessionId: sessionId, hostId: hostId, cwd: cwd, threadId: threadId)
                persistSessionMetadata()
                status = .connecting
                let nextSocket = try makeSessionSocket(
                    baseURL: baseURL,
                    token: token,
                    sessionId: sessionId,
                    lastSeq: 0,
                    sessionGeneration: generation,
                    configurationGeneration: configuration
                )
                guard generation == sessionGeneration,
                      configuration == configurationGeneration else {
                    nextSocket.close(endSession: true)
                    return
                }
                socket = nextSocket
            } catch {
                guard generation == sessionGeneration,
                      configuration == configurationGeneration else { return }
                status = .error
                errorMessage = error.localizedDescription
                resuming = false
            }
        }
    }

    private func makeSessionSocket(
        baseURL: String,
        token: String,
        sessionId: String,
        lastSeq: Int,
        sessionGeneration: Int,
        configurationGeneration: Int
    ) throws -> SessionSocket {
        try SessionSocket(
            baseURL: baseURL,
            userToken: token,
            sessionId: sessionId,
            lastSeq: lastSeq,
            onFrame: { [weak self] frame in
                guard let self,
                      sessionGeneration == self.sessionGeneration,
                      configurationGeneration == self.configurationGeneration else { return }
                self.handle(frame: frame)
            },
            onState: { [weak self] socketState in
                guard let self,
                      sessionGeneration == self.sessionGeneration,
                      configurationGeneration == self.configurationGeneration else { return }
                self.handleSocketState(socketState)
            }
        )
    }

    private func restorePersistedSessionIfPossible() {
        guard isConfigured,
              let scope = Self.normalizedRelayScope(relayURL),
              let key = Self.activeSessionKey(for: relayURL),
              let data = UserDefaults.standard.data(forKey: key),
              let saved = try? JSONDecoder().decode(PersistedSession.self, from: data) else {
            return
        }
        sessionGeneration += 1
        let generation = sessionGeneration
        let configuration = configurationGeneration
        sessionRelayScope = scope
        restoringPersistedSession = true
        session = SessionInfo(
            sessionId: saved.sessionId,
            hostId: saved.hostId,
            cwd: saved.cwd,
            threadId: saved.threadId
        )
        status = .connecting
        connectionMessage = "Restoring session…"
        do {
            socket = try makeSessionSocket(
                baseURL: relayURL,
                token: userToken,
                sessionId: saved.sessionId,
                // A recreated process has no in-memory transcript. Asking
                // from zero rebuilds the available relay tail and emits a
                // replay-gap marker when the 1000-frame buffer has wrapped.
                lastSeq: 0,
                sessionGeneration: generation,
                configurationGeneration: configuration
            )
        } catch {
            failPersistedRestore(error.localizedDescription)
        }
    }

    private func persistSessionMetadata() {
        guard let session,
              let scope = sessionRelayScope,
              let key = Self.activeSessionKey(for: scope),
              let data = try? JSONEncoder().encode(PersistedSession(
                sessionId: session.sessionId,
                hostId: session.hostId,
                cwd: session.cwd,
                threadId: session.threadId
              )) else { return }
        UserDefaults.standard.set(data, forKey: key)
    }

    private func clearPersistedSession() {
        guard let scope = sessionRelayScope,
              let key = Self.activeSessionKey(for: scope) else { return }
        UserDefaults.standard.removeObject(forKey: key)
        // Remove the cursor key written by early 0.2 development builds.
        UserDefaults.standard.removeObject(forKey: key + ".seq")
    }

    private func failPersistedRestore(_ message: String) {
        closeSession()
        errorMessage = "Could not restore the previous session: \(message)"
        if isConfigured { refreshHosts() }
    }

    func sendPrompt() {
        let input = prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        let images = pendingImages
        guard (!input.isEmpty || !images.isEmpty), let socket else { return }

        if images.isEmpty, let slash = Self.parseSlash(input) {
            guard socket.sendSlash(command: slash.command, args: slash.args) else {
                errorMessage = "Socket is not connected."
                return
            }
            prompt = ""
            applyOptimisticSlashMode(slash.command)
            appendSystem("You", "/\(slash.command)\(slash.args.isEmpty ? "" : " \(slash.args)")")
            return
        }

        let messageId = "msg-\(UUID().uuidString.prefix(8))"
        if pending {
            // A turn is running: steer it instead of being locked out. The
            // relay echoes the steered message like any user message.
            guard socket.sendSteer(input, clientMessageId: messageId, images: images) else {
                errorMessage = "Socket is not connected."
                return
            }
            prompt = ""
            pendingImages = []
            steerRecoveryInput = input
            steerRecoveryImages = images
            steerRecoveryMessageId = messageId
            if !images.isEmpty { sentImagesByMessageId[messageId] = images }
            return
        }
        // The relay echoes the user message back to every attached client,
        // including this one, as an item-started keyed by client_message_id.
        // Let that echo render it instead of appending a second copy here.
        unsentInput = input
        unsentMessageId = messageId
        unsentImages = images
        guard socket.sendTurn(
            input,
            clientMessageId: messageId,
            model: model,
            effort: effort,
            permissions: permissions,
            images: images
        ) else {
            unsentInput = nil
            unsentMessageId = nil
            unsentImages = []
            errorMessage = "Socket is not connected."
            return
        }
        prompt = ""
        pendingImages = []
        if !images.isEmpty { sentImagesByMessageId[messageId] = images }
        pending = true
    }

    func queuePrompt() {
        let input = prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        let images = pendingImages
        guard pending, !input.isEmpty || !images.isEmpty else { return }
        if images.isEmpty, Self.parseSlash(input) != nil {
            errorMessage = "Slash commands run immediately and cannot be queued."
            return
        }
        queuedTurns.append(makeTurn(input: input, images: images, queued: true))
        prompt = ""
        pendingImages = []
    }

    func removeQueuedTurn(_ id: String) {
        queuedTurns.removeAll { $0.id == id && !$0.sending }
    }

    func attachImage(data: Data, mime: String, label: String = "image") {
        let used = pendingImages.reduce(0) { $0 + $1.bytes }
        guard used + data.count <= Self.maxAttachmentBytes else {
            errorMessage = "Images for one message must stay under 25 MB."
            return
        }
        pendingImages.append(PendingImage(
            data: data,
            mime: mime.ifEmpty("image/jpeg"),
            label: String(label.suffix(32)).ifEmpty("image")
        ))
    }

    func removeImage(_ id: UUID) {
        pendingImages.removeAll { $0.id == id }
    }

    func sendSlash(_ command: String, args: String = "") {
        guard let socket, socket.sendSlash(command: command, args: args) else {
            errorMessage = "Socket is not connected."
            return
        }
        applyOptimisticSlashMode(command)
        appendSystem("You", "/\(command)\(args.isEmpty ? "" : " \(args)")")
    }

    func refreshGoal() {
        guard socket?.sendGoalGet() == true else {
            errorMessage = "Socket is not connected."
            return
        }
    }

    func setGoal(_ objective: String, tokenBudget: Int? = nil) {
        let cleaned = objective.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleaned.isEmpty else {
            errorMessage = "Goal objective is required."
            return
        }
        guard socket?.sendGoalSet(
            objective: cleaned,
            status: "active",
            tokenBudget: tokenBudget
        ) == true else {
            errorMessage = "Socket is not connected."
            return
        }
    }

    func pauseGoal() {
        guard socket?.sendGoalSet(status: "paused") == true else {
            errorMessage = "Socket is not connected."
            return
        }
    }

    func resumeGoal() {
        guard socket?.sendGoalSet(status: "active") == true else {
            errorMessage = "Socket is not connected."
            return
        }
    }

    func clearGoal() {
        guard socket?.sendGoalClear() == true else {
            errorMessage = "Socket is not connected."
            return
        }
    }

    static func parseSlash(_ raw: String) -> ParsedSlash? {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard text.hasPrefix("/") else { return nil }
        let bare = String(text.dropFirst()).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !bare.isEmpty else { return nil }
        let pieces = bare.split(maxSplits: 1, whereSeparator: { $0.isWhitespace })
        let command = pieces[0].lowercased()
        let args = pieces.count > 1
            ? String(pieces[1]).trimmingCharacters(in: .whitespacesAndNewlines)
            : ""
        return ParsedSlash(command: command, args: args)
    }

    func interruptTurn() {
        if socket?.sendInterrupt() != true {
            errorMessage = "Socket is not connected."
        }
    }

    private func makeTurn(
        input: String,
        images: [PendingImage],
        queued: Bool
    ) -> QueuedTurn {
        QueuedTurn(
            id: "\(queued ? "queued" : "turn")-\(UUID().uuidString.prefix(8))",
            clientMessageId: "msg-\(UUID().uuidString.prefix(8))",
            text: input,
            model: model,
            effort: effort,
            permissions: permissions,
            images: images
        )
    }

    private func drainQueuedTurn() {
        guard !pending, var turn = queuedTurns.first, !turn.sending, let socket else { return }
        guard socket.sendTurn(
            turn.text,
            clientMessageId: turn.clientMessageId,
            model: turn.model,
            effort: turn.effort,
            permissions: turn.permissions,
            images: turn.images
        ) else { return }
        turn.sending = true
        queuedTurns[0] = turn
        if !turn.images.isEmpty {
            sentImagesByMessageId[turn.clientMessageId] = turn.images
        }
        pending = true
    }

    static func resetSendingHead(_ turns: [QueuedTurn]) -> [QueuedTurn] {
        guard var first = turns.first, first.sending else { return turns }
        first.sending = false
        var updated = turns
        updated[0] = first
        return updated
    }

    static func acknowledgeQueuedTurn(
        _ turns: [QueuedTurn],
        clientMessageId: String
    ) -> [QueuedTurn] {
        turns.filter { !($0.sending && $0.clientMessageId == clientMessageId) }
    }

    private func applyOptimisticSlashMode(_ command: String) {
        if command == "plan" { planMode = true }
        if command == "default" { planMode = false }
    }

    /** Ask for the previous page of older turns. Guards re-entry. */
    func loadOlderHistory() {
        guard historyHasMore, !historyLoading, let socket else { return }
        guard socket.sendHistoryMore(before: historyOldest) else {
            errorMessage = "Socket is not connected."
            return
        }
        historyLoading = true
    }

    private func resetHistoryPaging() {
        historyHasMore = false
        historyLoading = false
        historyOldest = 0
        historyBuffer = nil
        historyAnchorId = nil
    }

    // Answers the head of the approval queue; popping it reveals the next.
    // If the relay cannot deliver the answer it re-pushes a pending-prompts
    // snapshot, which puts the prompt back.
    func resolveApproval(_ decision: String) {
        guard let head = pendingApprovals.first, let socket else { return }
        guard socket.sendApproval(approvalId: head.approvalId, decision: decision) else {
            errorMessage = "Socket is not connected."
            return
        }
        pendingApprovals.removeAll { $0.approvalId == head.approvalId }
    }

    // answers: { <question_id>: [string, ...] }
    func resolveUserInput(_ answers: [String: [String]]) {
        guard let head = pendingUserInputs.first, let socket else { return }
        guard socket.sendUserInput(callId: head.callId, answers: answers) else {
            errorMessage = "Socket is not connected."
            return
        }
        pendingUserInputs.removeAll { $0.callId == head.callId }
    }

    // Empty answers map → the daemon replies { answers: {} } and codex
    // treats every question as skipped.
    func cancelUserInput() {
        resolveUserInput([:])
    }

    /// Poll /api/hosts/{id}/telemetry every 3s, same cadence as the web
    /// sidebar. Cancelled when the session closes.
    func startTelemetry() {
        guard let hostId = session?.hostId else { return }
        telemetryTask?.cancel()
        let baseURL = relayURL
        let token = userToken
        telemetryTask = Task { [weak self, client] in
            while !Task.isCancelled {
                if let data = try? await client.hostTelemetry(
                    baseURL: baseURL, userToken: token, hostId: hostId
                ) {
                    await MainActor.run { self?.applyTelemetry(data) }
                }
                try? await Task.sleep(nanoseconds: 3_000_000_000)
            }
        }
    }

    func stopTelemetry() {
        telemetryTask?.cancel()
        telemetryTask = nil
    }

    private func applyTelemetry(_ data: HostTelemetryData) {
        telemetry = data
        func push(_ series: [Double], _ value: Double?) -> [Double] {
            let next = series + [value ?? 0]
            return next.count > 40 ? Array(next.suffix(40)) : next
        }
        telemetryCpu = push(telemetryCpu, data.cpu?.percent)
        telemetryMem = push(telemetryMem, data.memory?.percent)
        telemetryGpu = push(telemetryGpu, (data.gpus?.first ?? data.gpu)?.percent)
    }

    /// Workspace file listing for the session's cwd.
    func loadWorkspace(_ path: String? = nil) {
        guard let hostId = session?.hostId else { return }
        let target = path ?? workspacePath.ifEmpty(session?.cwd ?? "/")
        let generation = sessionGeneration
        let baseURL = relayURL
        let token = userToken
        errorMessage = nil
        workspaceLoading = true
        Task {
            do {
                let listing = try await client.readDirectory(
                    baseURL: baseURL, userToken: token, hostId: hostId, path: target
                )
                guard generation == sessionGeneration else { return }
                workspacePath = listing.path ?? target
                workspaceEntries = Self.sortedEntries(listing.entries)
                workspaceLoading = false
            } catch {
                guard generation == sessionGeneration else { return }
                workspaceLoading = false
                errorMessage = error.localizedDescription
            }
        }
    }

    func createWorkspaceDirectory(_ name: String) {
        guard let hostId = session?.hostId else { return }
        let path = workspacePath.ifEmpty(session?.cwd ?? "/")
        performWorkspaceMutation { client, baseURL, token in
            try await client.createDirectory(
                baseURL: baseURL,
                userToken: token,
                hostId: hostId,
                path: path,
                name: name
            )
        }
    }

    func renameWorkspaceEntry(_ entry: FsEntry, to newName: String) {
        guard let hostId = session?.hostId else { return }
        do {
            let safeName = try RelayClient.validatedFileName(newName)
            let source = Self.joinRemotePath(workspacePath, entry.fileName)
            let destination = Self.joinRemotePath(workspacePath, safeName)
            guard source != destination else { return }
            performWorkspaceMutation { client, baseURL, token in
                try await client.renameFile(
                    baseURL: baseURL,
                    userToken: token,
                    hostId: hostId,
                    from: source,
                    to: destination
                )
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func deleteWorkspaceFile(_ entry: FsEntry) {
        guard !entry.isDirectory, let hostId = session?.hostId else { return }
        let path = Self.joinRemotePath(workspacePath, entry.fileName)
        performWorkspaceMutation { client, baseURL, token in
            try await client.deleteFile(
                baseURL: baseURL,
                userToken: token,
                hostId: hostId,
                path: path
            )
        }
    }

    func uploadWorkspaceFile(from url: URL) {
        guard let hostId = session?.hostId else { return }
        let path = workspacePath.ifEmpty(session?.cwd ?? "/")
        let accessed = url.startAccessingSecurityScopedResource()
        defer { if accessed { url.stopAccessingSecurityScopedResource() } }
        do {
            let values = try url.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey])
            guard values.isRegularFile != false else {
                throw RelayClientError.invalidResponse("Choose a regular file to upload.")
            }
            if let size = values.fileSize { try RelayClient.validateFileSize(size) }
            let bytes = try Data(contentsOf: url)
            try RelayClient.validateFileSize(bytes.count)
            let fileName = try RelayClient.validatedFileName(url.lastPathComponent)
            performWorkspaceMutation { client, baseURL, token in
                try await client.uploadFile(
                    baseURL: baseURL,
                    userToken: token,
                    hostId: hostId,
                    directory: path,
                    fileName: fileName,
                    bytes: bytes
                )
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func downloadWorkspaceFile(_ entry: FsEntry) async -> URL? {
        guard !entry.isDirectory, let hostId = session?.hostId else { return nil }
        do {
            let file = try await client.readFile(
                baseURL: relayURL,
                userToken: userToken,
                hostId: hostId,
                path: Self.joinRemotePath(workspacePath, entry.fileName)
            )
            let directory = FileManager.default.temporaryDirectory
                .appendingPathComponent("remotex-share-\(UUID().uuidString)", isDirectory: true)
            try FileManager.default.createDirectory(
                at: directory,
                withIntermediateDirectories: true
            )
            let name = Self.safeShareFileName(file.name)
            let fileURL = directory.appendingPathComponent(name, isDirectory: false)
            try file.data.write(to: fileURL, options: .atomic)
            return fileURL
        } catch {
            errorMessage = error.localizedDescription
            return nil
        }
    }

    private func performWorkspaceMutation(
        _ operation: @escaping (
            RelayClient,
            String,
            String
        ) async throws -> Void
    ) {
        let generation = sessionGeneration
        let baseURL = relayURL
        let token = userToken
        errorMessage = nil
        workspaceLoading = true
        Task {
            do {
                try await operation(client, baseURL, token)
                guard generation == sessionGeneration else { return }
                workspaceLoading = false
                loadWorkspace(workspacePath)
            } catch {
                guard generation == sessionGeneration else { return }
                workspaceLoading = false
                errorMessage = error.localizedDescription
            }
        }
    }

    func closeSession(clearSelectedHost: Bool = true) {
        sessionGeneration += 1
        stopTelemetry()
        clearPersistedSession()
        socket?.close(endSession: true)
        socket = nil
        session = nil
        pending = false
        resuming = false
        historyOnly = false
        connectionMessage = nil
        stream = []
        resetHistoryPaging()
        clearPendingPrompts()
        unsentInput = nil
        unsentMessageId = nil
        unsentImages = []
        steerRecoveryInput = nil
        steerRecoveryImages = []
        steerRecoveryMessageId = nil
        sentImagesByMessageId = [:]
        pendingImages = []
        queuedTurns = []
        restoringPersistedSession = false
        sessionRelayScope = nil
        planMode = false
        goal = nil
        collaborationModes = []
        tokensInput = 0
        tokensOutput = 0
        tokensCached = 0
        tokensReasoning = 0
        status = .idle
        if clearSelectedHost {
            selectedHost = nil
        }
    }

    func reconnectAfterForeground() {
        socket?.resumeAfterForeground()
        inventorySocket?.resumeAfterForeground()
    }

    func setAppActive(_ active: Bool) {
        appIsActive = active
        if !active {
            persistSessionMetadata()
        }
    }

    private func handleSocketState(_ socketState: SessionSocketState) {
        switch socketState {
        case .connecting:
            status = .connecting
            connectionMessage = "Connecting…"
        case let .reconnecting(reason):
            // Keep the current turn and prompt queues. The relay's attached
            // snapshot is authoritative after transport recovery.
            status = .disconnected
            connectionMessage = reason
            queuedTurns = Self.resetSendingHead(queuedTurns)
        case let .stopped(reason):
            if restoringPersistedSession {
                failPersistedRestore(reason)
                return
            }
            status = .disconnected
            connectionMessage = reason
            pending = false
            resuming = false
            clearPendingPrompts()
            queuedTurns = []
            clearPersistedSession()
        }
    }

    /// Internal so the XCTest target can drive the actual reducer with
    /// captured relay frames without opening a WebSocket.
    func handle(frame: [String: Any]) {
        switch frame.string("type") {
        case "attached":
            restoringPersistedSession = false
            persistSessionMetadata()
            if !historyOnly {
                status = (frame.int("replay_from") ?? 0) > 0 && !resuming
                    ? .connected
                    : .connecting
                connectionMessage = resuming ? "Resuming saved chat…" : nil
            }
            if let active = frame.optionalBool("turn_in_flight") {
                pending = active
                if !active {
                    queuedTurns = Self.resetSendingHead(queuedTurns)
                    drainQueuedTurn()
                }
            }
        case "pending-prompts":
            applyPendingPrompts(frame)
        case "approval-resolved":
            // Answered here or by a peer client — either way this prompt
            // leaves the queue and the next one becomes the head.
            if let approvalId = frame.string("approval_id") {
                pendingApprovals.removeAll { $0.approvalId == approvalId }
            }
        case "user-input-resolved":
            if let callId = frame.string("call_id") {
                pendingUserInputs.removeAll { $0.callId == callId }
            }
        case "replay-gap":
            appendReplayGap(frame)
        case "session-closed":
            status = .disconnected
            pending = false
            resuming = false
            connectionMessage = "Session closed"
            clearPendingPrompts()
            queuedTurns = []
            clearPersistedSession()
        case "session-event":
            guard let event = frame.dictionary("event"),
                  let kind = event.string("kind") else {
                return
            }
            handleSessionEvent(kind: kind, data: event.dictionary("data") ?? [:])
        case "error":
            handleRelayError(
                frame.string("error") ?? "Relay error",
                fatal: frame.bool("fatal")
            )
        default:
            break
        }
    }

    // A relay error frame does not tear the socket down, so the session is
    // still usable — flipping `status` to .error here would wedge the
    // composer with no way back to .connected.
    private func handleRelayError(_ message: String, fatal: Bool = false) {
        if fatal, restoringPersistedSession {
            failPersistedRestore(message)
            return
        }
        errorMessage = message
        queuedTurns = Self.resetSendingHead(queuedTurns)
        if message == "no turn is running to steer" {
            restoreSteerInput()
        }
        if fatal {
            status = .disconnected
            connectionMessage = message
            pending = false
            resuming = false
            clearPendingPrompts()
            queuedTurns = []
            clearPersistedSession()
            return
        }
        guard message == Self.turnBusyError else { return }
        // A turn really is running (ours or a peer's), so keep send
        // disabled, and hand the rejected text back to the composer.
        pending = true
        if prompt.isEmpty, let unsent = unsentInput {
            prompt = unsent
        }
        if pendingImages.isEmpty, !unsentImages.isEmpty {
            pendingImages = unsentImages
        }
        if let unsentMessageId {
            sentImagesByMessageId.removeValue(forKey: unsentMessageId)
        }
        unsentInput = nil
        unsentMessageId = nil
        unsentImages = []
    }

    private func restoreSteerInput() {
        if prompt.isEmpty, let input = steerRecoveryInput {
            prompt = input
        }
        if pendingImages.isEmpty, !steerRecoveryImages.isEmpty {
            pendingImages = steerRecoveryImages
        }
        if let steerRecoveryMessageId {
            sentImagesByMessageId.removeValue(forKey: steerRecoveryMessageId)
        }
        steerRecoveryInput = nil
        steerRecoveryImages = []
        steerRecoveryMessageId = nil
    }

    private func applyResolvedSettings(_ settings: [String: Any]) {
        if let value = settings.string("model"), !value.isEmpty {
            model = value
            session?.model = value
        }
        if let value = settings.string("effort"), !value.isEmpty {
            effort = value
        }
        if let value = settings.string("permissions"), !value.isEmpty {
            permissions = value
        }
    }

    private func handleSessionEvent(kind: String, data: [String: Any]) {
        switch kind {
        case "session-started":
            session?.model = data.string("model") ?? session?.model
            session?.cwd = data.string("cwd") ?? session?.cwd
            session?.threadId = data.string("thread_id") ?? session?.threadId
            if let settings = data.dictionary("settings") {
                applyResolvedSettings(settings)
            }
            historyOnly = data.string("transport") == "history"
            resuming = data.bool("resuming")
            persistSessionMetadata()
            if historyOnly {
                status = .error
                pending = false
                connectionMessage = "History only"
                errorMessage = "Saved chat is history-only. Start a new session to continue."
            } else if resuming {
                status = .connecting
                connectionMessage = "Resuming saved chat…"
            } else {
                status = .connected
                connectionMessage = nil
                errorMessage = nil
                requestNotificationPermissionIfNeeded()
            }

        case "history-begin":
            historyBuffer = []
            historyBufferPrepend = false

        case "history-chunk-begin":
            historyBuffer = []
            historyBufferPrepend = true

        case "history-end", "history-chunk-end":
            commitHistoryBatch(data)

        case "item-started":
            if historyBuffer != nil, data.bool("replayed") {
                if let item = makeStreamItem(from: data) {
                    historyBuffer?.append(item)
                }
                return
            }
            appendStartedItem(data)

        case "item-delta":
            guard let itemId = data.string("item_id") else { return }
            let delta = data.string("delta") ?? ""
            updateItem(id: itemId) { item in
                item.text += delta
            }

        case "item-patch":
            // Progressive file-edit diff: codex resends the WHOLE patch each
            // time, so replace the body rather than appending to it.
            guard let itemId = data.string("item_id") else { return }
            let output = data.string("output") ?? ""
            updateItem(id: itemId) { item in
                item.text = output
            }

        case "item-completed":
            // Replayed completions mirror their item-started payloads.
            if historyBuffer != nil, data.bool("replayed") { return }
            guard let itemId = data.string("item_id") else { return }
            updateItem(id: itemId) { item in
                if let text = data.string("text"), !text.isEmpty {
                    item.text = text
                }
                if let output = data.string("output"), !output.isEmpty {
                    item.text = output
                }
                item.completed = true
            }

        case "turn-started":
            pending = true

        case "turn-completed":
            let wasPending = pending
            pending = false
            steerRecoveryInput = nil
            steerRecoveryImages = []
            if let steerRecoveryMessageId {
                sentImagesByMessageId.removeValue(forKey: steerRecoveryMessageId)
            }
            steerRecoveryMessageId = nil
            // The relay drops every outstanding prompt for the session when
            // a turn ends, so both queues empty together.
            clearPendingPrompts()
            if let error = data.string("error"), !error.isEmpty {
                errorMessage = error
                if prompt.isEmpty, let unsentInput {
                    prompt = unsentInput
                }
                if pendingImages.isEmpty, !unsentImages.isEmpty {
                    pendingImages = unsentImages
                }
                if let unsentMessageId {
                    sentImagesByMessageId.removeValue(forKey: unsentMessageId)
                }
                unsentInput = nil
                unsentMessageId = nil
                unsentImages = []
            }
            if Self.shouldNotifyTurnCompletion(
                appIsActive: appIsActive,
                wasPending: wasPending,
                replayed: data.bool("replayed")
            ) {
                postTurnCompletionNotification()
            }
            drainQueuedTurn()

        case "steer-failed":
            restoreSteerInput()
            errorMessage = data.string("error") ?? "Could not steer this turn."

        case "approval-request":
            guard let approval = approvalPrompt(from: data) else { return }
            enqueueApproval(approval)

        case "user-input-request":
            guard let request = userInputPrompt(from: data) else { return }
            enqueueUserInput(request)

        case "slash-ack":
            let command = data.string("command") ?? "slash"
            let ok = data.bool("ok")
            if ok { applyOptimisticSlashMode(command) }
            let detail = data.string("message")
                ?? data.string("error")
                ?? (ok ? "ok" : "failed")
            if ok, command == "cd", detail.hasPrefix("cwd → ") {
                session?.cwd = String(detail.dropFirst("cwd → ".count))
                persistSessionMetadata()
            }
            appendSystem(ok ? "/\(command)" : "/\(command) failed", detail)

        case "collab-modes":
            collaborationModes = (data["modes"] as? [[String: Any]] ?? [])
                .compactMap { $0.string("name") }
            appendSystem(
                "Collaboration modes",
                collaborationModes.isEmpty ? "none reported" : collaborationModes.joined(separator: ", ")
            )

        case "goal-snapshot", "goal-updated":
            goal = Self.parseGoal(data.dictionary("goal"))

        case "goal-cleared":
            goal = nil

        case "token-usage":
            tokensInput = data.int("input") ?? tokensInput
            tokensOutput = data.int("output") ?? tokensOutput
            tokensCached = data.int("cached_input") ?? tokensCached
            tokensReasoning = data.int("reasoning_output") ?? tokensReasoning

        case "thread-status":
            switch data.string("status") {
            case "resuming":
                resuming = true
                status = .connecting
                connectionMessage = "Resuming saved chat…"
                if let threadId = data.string("thread_id") {
                    session?.threadId = threadId
                    persistSessionMetadata()
                }
            case "resumed":
                resuming = false
                historyOnly = false
                status = .connected
                connectionMessage = nil
                errorMessage = nil
                session?.model = data.string("model") ?? session?.model
                session?.cwd = data.string("cwd") ?? session?.cwd
                session?.threadId = data.string("thread_id") ?? session?.threadId
                persistSessionMetadata()
                requestNotificationPermissionIfNeeded()
                if let settings = data.dictionary("settings") {
                    applyResolvedSettings(settings)
                }
                if let active = data.optionalBool("shared_turn_in_flight") {
                    pending = active
                    if !active { drainQueuedTurn() }
                }
            case "resume-failed":
                resuming = false
                status = .error
                pending = false
                connectionMessage = "Resume failed"
                errorMessage = data.string("error") ?? "Saved chat could not be resumed."
            case let value?:
                appendSystem("Thread", value)
            case nil:
                break
            }

        case "session-settings":
            applyResolvedSettings(data)

        default:
            break
        }
    }

    static func shouldNotifyTurnCompletion(
        appIsActive: Bool,
        wasPending: Bool,
        replayed: Bool
    ) -> Bool {
        !appIsActive && wasPending && !replayed
    }

    private func requestNotificationPermissionIfNeeded() {
        guard !UserDefaults.standard.bool(forKey: Self.notificationPermissionKey) else { return }
        UserDefaults.standard.set(true, forKey: Self.notificationPermissionKey)
        Task {
            _ = try? await UNUserNotificationCenter.current().requestAuthorization(
                options: [.alert, .sound]
            )
        }
    }

    private func postTurnCompletionNotification() {
        let content = UNMutableNotificationContent()
        content.title = "Codex finished"
        content.body = "Your Remotex turn is ready."
        content.sound = .default
        let sessionId = session?.sessionId ?? "session"
        let request = UNNotificationRequest(
            identifier: "remotex-turn-\(sessionId)-\(UUID().uuidString)",
            content: content,
            trigger: nil
        )
        UNUserNotificationCenter.current().add(request) { _ in }
    }

    static func parseGoal(_ raw: [String: Any]?) -> ThreadGoal? {
        guard let raw else { return nil }
        let normalized = (raw.string("status") ?? "")
            .replacingOccurrences(of: "-", with: "")
            .replacingOccurrences(of: "_", with: "")
            .lowercased()
        let status: String
        switch normalized {
        case "budgetlimited": status = "budgetLimited"
        case "active": status = "active"
        case "paused": status = "paused"
        case "complete": status = "complete"
        default: status = raw.string("status") ?? ""
        }
        return ThreadGoal(
            threadId: raw.string("thread_id") ?? raw.string("threadId") ?? "",
            objective: raw.string("objective") ?? "",
            status: status,
            tokenBudget: raw.int("token_budget") ?? raw.int("tokenBudget"),
            tokensUsed: raw.int("tokens_used") ?? raw.int("tokensUsed") ?? 0,
            timeUsedSeconds: raw.int("time_used_seconds") ?? raw.int("timeUsedSeconds") ?? 0
        )
    }

    private func clearConfigurationDependentState() {
        inventorySocket?.close()
        inventorySocket = nil
        if socket != nil || session != nil {
            closeSession()
        }
        hosts = []
        selectedHost = nil
        threads = []
        threadsHost = nil
        modelOptions = []
        startEntries = []
        startPath = "/"
        startLoading = false
        status = .idle
        connectionMessage = nil
        errorMessage = nil
    }

    static func sortedEntries(_ entries: [FsEntry]) -> [FsEntry] {
        entries.sorted { a, b in
            if a.isDirectory != b.isDirectory { return a.isDirectory }
            let aDot = a.fileName.hasPrefix(".")
            let bDot = b.fileName.hasPrefix(".")
            if aDot != bDot { return !aDot }
            return a.fileName.localizedCaseInsensitiveCompare(b.fileName) == .orderedAscending
        }
    }

    static func parentRemotePath(_ path: String) -> String {
        guard path != "/", !path.isEmpty else { return "/" }
        let trimmed = path.hasSuffix("/") ? String(path.dropLast()) : path
        guard let slash = trimmed.lastIndex(of: "/") else { return "/" }
        let parent = String(trimmed[..<slash])
        return parent.isEmpty ? "/" : parent
    }

    static func joinRemotePath(_ base: String, _ name: String) -> String {
        let root = base.ifEmpty("/")
        return root.hasSuffix("/") ? root + name : "\(root)/\(name)"
    }

    static func safeShareFileName(_ raw: String) -> String {
        let slashNormalized = raw.replacingOccurrences(of: "\\", with: "/")
        // Treat relay-provided names as opaque strings. URL(fileURLWithPath:)
        // resolves relative dot components on Darwin, so ".." unexpectedly
        // becomes "/" instead of remaining a basename we can reject.
        let basename = slashNormalized
            .split(separator: "/", omittingEmptySubsequences: true)
            .last
            .map(String.init) ?? ""
        let name = basename
            .unicodeScalars
            .filter { !CharacterSet.controlCharacters.contains($0) }
            .map(String.init)
            .joined()
        return name.isEmpty || name == "." || name == ".." ? "download.bin" : name
    }

    // --- pending prompts ---------------------------------------------------

    // The relay's snapshot carries EVERY unanswered prompt for the session
    // in arrival order, so it is authoritative about both which prompts are
    // still open and where they sit in the queue.
    private func applyPendingPrompts(_ frame: [String: Any]) {
        let approvals = frame["approvals"] as? [[String: Any]] ?? []
        let userInputs = frame["user_inputs"] as? [[String: Any]] ?? []
        pendingApprovals = approvals.compactMap { approvalPrompt(from: $0) }
        pendingUserInputs = userInputs.compactMap { userInputPrompt(from: $0) }
    }

    // Keyed by approval_id so a replayed frame updates in place instead of
    // double-inserting, and never displaces an earlier unanswered prompt.
    private func enqueueApproval(_ approval: ApprovalPrompt) {
        if let index = pendingApprovals.firstIndex(where: { $0.approvalId == approval.approvalId }) {
            pendingApprovals[index] = approval
        } else {
            pendingApprovals.append(approval)
        }
    }

    private func enqueueUserInput(_ request: UserInputPrompt) {
        if let index = pendingUserInputs.firstIndex(where: { $0.callId == request.callId }) {
            pendingUserInputs[index] = request
        } else {
            pendingUserInputs.append(request)
        }
    }

    private func clearPendingPrompts() {
        pendingApprovals = []
        pendingUserInputs = []
    }

    private func approvalPrompt(from data: [String: Any]) -> ApprovalPrompt? {
        guard let approvalId = data.string("approval_id") else { return nil }
        let decisions: [String]
        if let offered = data["decisions"] as? [Any] {
            // Presence is authoritative, including an explicitly empty list.
            decisions = offered.compactMap { $0 as? String }
        } else {
            decisions = ["accept", "acceptForSession", "decline", "cancel"]
        }
        return ApprovalPrompt(
            approvalId: approvalId,
            kind: data.string("kind"),
            reason: data.string("reason"),
            command: data.string("command"),
            cwd: data.string("cwd"),
            permissions: Self.prettyJSON(data["permissions"]),
            decisions: decisions
        )
    }

    private func userInputPrompt(from data: [String: Any]) -> UserInputPrompt? {
        guard let callId = data.string("call_id") else { return nil }
        let rawQuestions = data["questions"] as? [[String: Any]] ?? []
        let questions: [UserInputQuestion] = rawQuestions.compactMap { raw in
            guard let id = raw.string("id") else { return nil }
            let options = (raw["options"] as? [[String: Any]] ?? []).compactMap { option -> UserInputOption? in
                guard let label = option.string("label"), !label.isEmpty else { return nil }
                return UserInputOption(label: label, description: option.string("description") ?? "")
            }
            return UserInputQuestion(
                id: id,
                header: raw.string("header") ?? "",
                question: raw.string("question") ?? "",
                isSecret: raw.bool("isSecret") || raw.bool("is_secret"),
                options: options
            )
        }
        return UserInputPrompt(callId: callId, questions: questions)
    }

    private static func prettyJSON(_ value: Any?) -> String? {
        guard let value, JSONSerialization.isValidJSONObject(value),
              let data = try? JSONSerialization.data(
                withJSONObject: value,
                options: [.prettyPrinted, .sortedKeys]
              ) else {
            return nil
        }
        return String(data: data, encoding: .utf8)
    }

    // --- stream ------------------------------------------------------------

    /// One state mutation per replay batch: the buffered tail (or an older
    /// page) lands in a single render instead of streaming past the user.
    private func commitHistoryBatch(_ data: [String: Any]) {
        let incoming = historyBuffer ?? []
        let prepend = historyBufferPrepend
        historyBuffer = nil
        historyOldest = data.int("oldest") ?? 0
        historyHasMore = data.bool("has_more")
        historyLoading = false
        stream.removeAll { $0.id.hasPrefix("preview_") }
        let seen = Set(stream.map(\.id))
        let fresh = incoming.filter { !seen.contains($0.id) }
        if !fresh.isEmpty {
            // Prepending before existing rows is right for both cases — on
            // the initial tail, `stream` holds at most live frames that
            // raced in.
            historyAnchorId = prepend ? stream.first?.id : nil
            stream.insert(contentsOf: fresh, at: 0)
        }
        if prepend {
            historyChunkTick += 1
        } else {
            historyTailTick += 1
        }
    }

    private func appendStartedItem(_ data: [String: Any]) {
        guard let item = makeStreamItem(from: data) else { return }
        let itemId = item.id
        let itemType = data.string("item_type") ?? "event"
        appendOnce(item)

        guard itemType == "user_message" else { return }
        sentImagesByMessageId.removeValue(forKey: itemId)
        if itemId == unsentMessageId {
            // Our own message came back from the relay: it reached codex,
            // so stop holding the text for the retry path.
            unsentInput = nil
            unsentMessageId = nil
            unsentImages = []
        }
        queuedTurns = Self.acknowledgeQueuedTurn(
            queuedTurns,
            clientMessageId: itemId
        )
        if !data.bool("replayed") {
            pending = true
        }
    }

    private func makeStreamItem(from data: [String: Any]) -> StreamItem? {
        let itemId = data.string("item_id") ?? "item-\(UUID().uuidString.prefix(8))"
        let itemType = data.string("item_type") ?? "event"
        let args = data.dictionary("args")

        let item: StreamItem
        switch itemType {
        case "agent_reasoning":
            item = StreamItem(
                id: itemId,
                role: .reasoning,
                title: "Reasoning",
                text: data.string("text") ?? "",
                completed: data.bool("replayed")
            )
        case "agent_message":
            item = StreamItem(
                id: itemId,
                role: .agent,
                title: "Codex",
                text: data.string("text") ?? "",
                completed: data.bool("replayed")
            )
        case "tool_call":
            item = StreamItem(
                id: itemId,
                role: .tool,
                title: data.string("tool") ?? "Tool",
                text: data.string("output") ?? "",
                detail: args?.string("command") ?? "",
                completed: data.bool("replayed")
            )
        case "user_message":
            let localImages = sentImagesByMessageId[itemId] ?? []
            item = StreamItem(
                id: itemId,
                role: .user,
                title: "You",
                text: data.string("text") ?? "",
                completed: true,
                imageCount: max(data.int("image_count") ?? 0, localImages.count),
                imageData: localImages.map(\.data)
            )
        default:
            item = StreamItem(
                id: itemId,
                role: .system,
                title: itemType,
                text: data.string("text") ?? "",
                completed: true
            )
        }
        return item
    }

    // Contract (C): the relay evicted frames we asked to replay. Mark the
    // hole so a truncated transcript never reads as a complete one.
    private func appendReplayGap(_ frame: [String: Any]) {
        let from = frame.int("missed_from") ?? 0
        let to = frame.int("missed_to") ?? 0
        appendOnce(StreamItem(
            id: "replay-gap-\(frame.string("session_id") ?? "")-\(from)-\(to)",
            role: .gap,
            title: "Earlier events unavailable",
            text: "Events \(from)-\(to) are no longer in the relay's replay buffer.",
            completed: true
        ))
    }

    private func appendSystem(_ title: String, _ text: String) {
        stream.append(StreamItem(
            id: "system-\(UUID().uuidString.prefix(8))",
            role: .system,
            title: title,
            text: text,
            completed: true
        ))
    }

    // Echoes and replays can deliver the same item id more than once.
    private func appendOnce(_ item: StreamItem) {
        guard !stream.contains(where: { $0.id == item.id }) else { return }
        stream.append(item)
    }

    private func updateItem(id: String, update: (inout StreamItem) -> Void) {
        guard let index = stream.firstIndex(where: { $0.id == id }) else { return }
        update(&stream[index])
    }
}

private extension Dictionary where Key == String, Value == Any {
    func string(_ key: String) -> String? {
        self[key] as? String
    }

    func dictionary(_ key: String) -> [String: Any]? {
        self[key] as? [String: Any]
    }

    func int(_ key: String) -> Int? {
        if let value = self[key] as? Int {
            return value
        }
        if let value = self[key] as? NSNumber {
            return value.intValue
        }
        if let value = self[key] as? String {
            return Int(value)
        }
        return nil
    }

    func bool(_ key: String) -> Bool {
        if let value = self[key] as? Bool {
            return value
        }
        if let value = self[key] as? NSNumber {
            return value.boolValue
        }
        if let value = self[key] as? String {
            return value == "true"
        }
        return false
    }

    func optionalBool(_ key: String) -> Bool? {
        guard self[key] != nil else { return nil }
        return bool(key)
    }
}
