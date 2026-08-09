import Foundation
import Combine

@MainActor
final class RemotexViewModel: ObservableObject {
    @Published var relayURL: String {
        didSet { UserDefaults.standard.set(relayURL, forKey: Self.relayURLKey) }
    }
    @Published var userToken: String {
        didSet { Keychain.set(userToken, for: Self.userTokenKey) }
    }
    @Published private(set) var hosts: [Host] = []
    @Published private(set) var selectedHost: Host?
    @Published private(set) var status: ConnectionStatus = .idle
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

    var hasPendingPrompts: Bool {
        !pendingApprovals.isEmpty || !pendingUserInputs.isEmpty
    }

    private static let relayURLKey = "remotex.relayURL"
    // Doubles as the Keychain account and as the legacy UserDefaults key
    // we migrate off on first launch.
    private static let userTokenKey = "remotex.userToken"
    // The relay rejects a turn-start while a turn is still running.
    private static let turnBusyError = "a turn is already running in this chat"

    private let client = RelayClient()
    private var socket: SessionSocket?
    // Kept until the relay echoes our user message back, so a rejected
    // turn-start can hand the text back to the composer.
    private var unsentInput: String?
    private var unsentMessageId: String?

    init() {
        self.relayURL = UserDefaults.standard.string(forKey: Self.relayURLKey) ?? "http://localhost:8080"
        self.userToken = Self.loadUserToken()
    }

    // The bearer token used to sit in UserDefaults, which lands in plaintext
    // in an unencrypted backup. Move any existing value into the Keychain
    // once, then drop the UserDefaults copy.
    private static func loadUserToken() -> String {
        let legacy = UserDefaults.standard.string(forKey: userTokenKey)
        if let legacy, !legacy.isEmpty, Keychain.string(for: userTokenKey) == nil {
            Keychain.set(legacy, for: userTokenKey)
        }
        if legacy != nil {
            UserDefaults.standard.removeObject(forKey: userTokenKey)
        }
        return Keychain.string(for: userTokenKey) ?? "demo-user-token"
    }

    func refreshHosts() {
        status = .loading
        errorMessage = nil
        Task {
            do {
                hosts = try await client.listHosts(baseURL: relayURL, userToken: userToken)
                status = socket == nil ? .idle : status
            } catch {
                status = .error
                errorMessage = error.localizedDescription
            }
        }
    }

    func openSession(host: Host) {
        guard host.online else {
            errorMessage = "\(host.nickname) is offline"
            return
        }
        openSession(hostId: host.id, host: host)
    }

    private func openSession(
        hostId: String,
        host: Host?,
        threadId: String? = nil,
        cwd: String? = nil
    ) {
        closeSession(clearSelectedHost: false)
        selectedHost = host
        status = .opening
        errorMessage = nil
        stream = []

        Task {
            do {
                let sessionId = try await client.openSession(
                    baseURL: relayURL,
                    userToken: userToken,
                    hostId: hostId,
                    threadId: threadId,
                    cwd: cwd
                )
                session = SessionInfo(sessionId: sessionId, hostId: hostId, cwd: cwd, threadId: threadId)
                status = .connecting
                socket = try SessionSocket(
                    baseURL: relayURL,
                    userToken: userToken,
                    sessionId: sessionId,
                    onFrame: { [weak self] frame in
                        Task { @MainActor in
                            self?.handle(frame: frame)
                        }
                    },
                    onClose: { [weak self] reason in
                        Task { @MainActor in
                            self?.handleClose(reason: reason)
                        }
                    }
                )
            } catch {
                status = .error
                errorMessage = error.localizedDescription
            }
        }
    }

    func sendPrompt() {
        let input = prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !input.isEmpty, !pending, let socket else { return }
        // The relay echoes the user message back to every attached client,
        // including this one, as an item-started keyed by client_message_id.
        // Let that echo render it instead of appending a second copy here.
        let messageId = "msg-\(UUID().uuidString.prefix(8))"
        unsentInput = input
        unsentMessageId = messageId
        prompt = ""
        pending = true
        socket.sendTurn(input, clientMessageId: messageId)
    }

    // Answers the head of the approval queue; popping it reveals the next.
    // If the relay cannot deliver the answer it re-pushes a pending-prompts
    // snapshot, which puts the prompt back.
    func resolveApproval(_ decision: String) {
        guard let head = pendingApprovals.first, let socket else { return }
        socket.sendApproval(approvalId: head.approvalId, decision: decision)
        pendingApprovals.removeAll { $0.approvalId == head.approvalId }
    }

    // answers: { <question_id>: [string, ...] }
    func resolveUserInput(_ answers: [String: [String]]) {
        guard let head = pendingUserInputs.first, let socket else { return }
        socket.sendUserInput(callId: head.callId, answers: answers)
        pendingUserInputs.removeAll { $0.callId == head.callId }
    }

    // Empty answers map → the daemon replies { answers: {} } and codex
    // treats every question as skipped.
    func cancelUserInput() {
        resolveUserInput([:])
    }

    func closeSession(clearSelectedHost: Bool = true) {
        socket?.close()
        socket = nil
        session = nil
        pending = false
        stream = []
        clearPendingPrompts()
        unsentInput = nil
        unsentMessageId = nil
        status = .idle
        if clearSelectedHost {
            selectedHost = nil
        }
    }

    private func handleClose(reason: String) {
        guard socket != nil else { return }
        socket = nil
        pending = false
        // Nothing can carry an answer to codex any more.
        clearPendingPrompts()
        status = .disconnected
        appendSystem("Disconnected", reason)
    }

    /// Reduce one relay frame into published state.
    ///
    /// Internal rather than private so `RemotexTests` can drive it directly
    /// with captured frames — it's the whole client-side protocol surface, and
    /// the only part testable without a relay. Everything it calls stays
    /// private; tests go through real frames, not internals.
    func handle(frame: [String: Any]) {
        switch frame.string("type") {
        case "attached":
            status = .connected
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
            clearPendingPrompts()
        case "session-event":
            guard let event = frame.dictionary("event"),
                  let kind = event.string("kind") else {
                return
            }
            handleSessionEvent(kind: kind, data: event.dictionary("data") ?? [:])
        case "error":
            handleRelayError(frame.string("error") ?? "Relay error")
        default:
            break
        }
    }

    // A relay error frame does not tear the socket down, so the session is
    // still usable — flipping `status` to .error here would wedge the
    // composer with no way back to .connected.
    private func handleRelayError(_ message: String) {
        errorMessage = message
        guard message == Self.turnBusyError else { return }
        // A turn really is running (ours or a peer's), so keep send
        // disabled, and hand the rejected text back to the composer.
        pending = true
        if prompt.isEmpty, let unsent = unsentInput {
            prompt = unsent
        }
        unsentInput = nil
        unsentMessageId = nil
    }

    private func handleSessionEvent(kind: String, data: [String: Any]) {
        switch kind {
        case "session-started":
            session?.model = data.string("model") ?? session?.model
            session?.cwd = data.string("cwd") ?? session?.cwd
            session?.threadId = data.string("thread_id") ?? session?.threadId
            status = .connected

        case "item-started":
            appendStartedItem(data)

        case "item-delta":
            guard let itemId = data.string("item_id") else { return }
            let delta = data.string("delta") ?? ""
            updateItem(id: itemId) { item in
                item.text += delta
            }

        case "item-patch":
            // Progressive file-edit diff. Codex resends the whole patch each
            // time, so replace the body rather than appending to it.
            guard let itemId = data.string("item_id") else { return }
            let output = data.string("output") ?? ""
            updateItem(id: itemId) { item in
                item.text = output
            }

        case "item-completed":
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
            pending = false
            // The relay drops every outstanding prompt for the session when
            // a turn ends, so both queues empty together.
            clearPendingPrompts()
            if let error = data.string("error"), !error.isEmpty {
                errorMessage = error
            }

        case "approval-request":
            guard let approval = approvalPrompt(from: data) else { return }
            enqueueApproval(approval)

        case "user-input-request":
            guard let request = userInputPrompt(from: data) else { return }
            enqueueUserInput(request)

        case "slash-ack":
            let command = data.string("command") ?? "slash"
            appendSystem("/\(command)", data.string("message") ?? data.string("error") ?? "ok")

        case "thread-status":
            appendSystem("Thread", data.string("status") ?? "")

        default:
            break
        }
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
        let decisions = (data["decisions"] as? [Any])?.compactMap { $0 as? String } ?? []
        return ApprovalPrompt(
            approvalId: approvalId,
            kind: data.string("kind"),
            reason: data.string("reason"),
            command: data.string("command"),
            cwd: data.string("cwd"),
            // Same fallback set as useRemotex.js and RemotexViewModel.kt —
            // a prompt that names no decisions still accepts all of them.
            decisions: decisions.isEmpty
                ? ["accept", "acceptForSession", "decline", "cancel"]
                : decisions
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
                options: options
            )
        }
        return UserInputPrompt(callId: callId, questions: questions)
    }

    // --- stream ------------------------------------------------------------

    private func appendStartedItem(_ data: [String: Any]) {
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
            item = StreamItem(
                id: itemId,
                role: .user,
                title: "You",
                text: data.string("text") ?? "",
                completed: true
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
        appendOnce(item)

        guard itemType == "user_message" else { return }
        if itemId == unsentMessageId {
            // Our own message came back from the relay: it reached codex,
            // so stop holding the text for the retry path.
            unsentInput = nil
            unsentMessageId = nil
        }
        if !data.bool("replayed") {
            pending = true
        }
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
}
