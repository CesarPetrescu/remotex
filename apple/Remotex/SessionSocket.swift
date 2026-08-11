import Foundation

enum SessionSocketError: LocalizedError {
    case invalidURL

    var errorDescription: String? {
        "Invalid WebSocket URL"
    }
}

enum SessionSocketState: Equatable {
    case connecting
    case reconnecting(String)
    case stopped(String)
}

/// One reconnecting relay attachment. It keeps the relay replay cursor in the
/// same object across transport drops, so callers only reduce semantic frames.
@MainActor
final class SessionSocket {
    private static let clientIdKey = "remotex.appleClientId"
    private static let heartbeatIntervalNs: UInt64 = 20_000_000_000
    private static let heartbeatStaleSeconds: TimeInterval = 70
    static let maximumMessageBytes = ((RemotexViewModel.maxAttachmentBytes * 4 + 2) / 3)
        + 4 * 1024 * 1024

    private let url: URL
    private let userToken: String
    private let sessionId: String
    private let clientId: String
    private let onFrame: @MainActor ([String: Any]) -> Void
    private let onState: @MainActor (SessionSocketState) -> Void

    private var task: URLSessionWebSocketTask?
    private var reconnectTask: Task<Void, Never>?
    private var heartbeatTask: Task<Void, Never>?
    private var generation = 0
    private var droppedGeneration: Int?
    private var reconnectAttempt = 0
    private var attached = false
    private var stopped = false
    private var lastMessageAt = Date()
    private(set) var lastSeq: Int

    init(
        baseURL: String,
        userToken: String,
        sessionId: String,
        clientId: String? = nil,
        lastSeq: Int = 0,
        onFrame: @escaping @MainActor ([String: Any]) -> Void,
        onState: @escaping @MainActor (SessionSocketState) -> Void
    ) throws {
        guard let url = Self.webSocketURL(baseURL: baseURL) else {
            throw SessionSocketError.invalidURL
        }
        self.url = url
        self.userToken = userToken
        self.sessionId = sessionId
        self.clientId = clientId ?? Self.stableClientId()
        self.lastSeq = lastSeq
        self.onFrame = onFrame
        self.onState = onState
        connect()
    }

    @discardableResult
    func sendTurn(
        _ input: String,
        clientMessageId: String,
        model: String = "",
        effort: String = "",
        permissions: String = "",
        images: [PendingImage] = []
    ) -> Bool {
        send(Self.turnFrame(
            input: input,
            clientMessageId: clientMessageId,
            model: model,
            effort: effort,
            permissions: permissions,
            images: images
        ))
    }

    @discardableResult
    func sendInterrupt() -> Bool {
        send(["type": "turn-interrupt"])
    }

    @discardableResult
    func sendSteer(
        _ input: String,
        clientMessageId: String,
        images: [PendingImage] = []
    ) -> Bool {
        var frame: [String: Any] = [
            "type": "turn-steer",
            "input": input,
            "client_message_id": clientMessageId,
        ]
        if !images.isEmpty { frame["images"] = Self.imagePayloads(images) }
        return send(frame)
    }

    @discardableResult
    func sendSlash(command: String, args: String = "") -> Bool {
        send(Self.slashFrame(command: command, args: args))
    }

    @discardableResult
    func sendGoalGet() -> Bool {
        send(["type": "goal-get"])
    }

    @discardableResult
    func sendGoalSet(
        objective: String? = nil,
        status: String? = nil,
        tokenBudget: Int? = nil
    ) -> Bool {
        var frame: [String: Any] = ["type": "goal-set"]
        if let objective { frame["objective"] = objective }
        if let status { frame["status"] = status }
        if let tokenBudget { frame["token_budget"] = tokenBudget }
        return send(frame)
    }

    @discardableResult
    func sendGoalClear() -> Bool {
        send(["type": "goal-clear"])
    }

    @discardableResult
    func sendHistoryMore(before: Int, limit: Int = 10) -> Bool {
        send([
            "type": "history-more",
            "before": before,
            "limit": limit,
        ])
    }

    @discardableResult
    func sendApproval(approvalId: String, decision: String) -> Bool {
        send([
            "type": "approval-response",
            "approval_id": approvalId,
            "decision": decision,
        ])
    }

    @discardableResult
    func sendUserInput(callId: String, answers: [String: [String]]) -> Bool {
        let wire: [String: [String: [String]]] = answers.mapValues { ["answers": $0] }
        return send([
            "type": "user-input-response",
            "call_id": callId,
            "answers": wire,
        ])
    }

    /// Called when the app becomes active. A fresh attachment is unnecessary
    /// when a recent heartbeat already proved this transport alive.
    func resumeAfterForeground() {
        guard !stopped else { return }
        let age = Date().timeIntervalSince(lastMessageAt)
        if attached, age < Self.heartbeatStaleSeconds / 2 {
            startHeartbeat(for: generation)
            return
        }
        if !attached, task?.state == .running, age < 10 {
            return
        }
        reconnectTask?.cancel()
        reconnectTask = nil
        reconnectAttempt = 0
        task?.cancel(with: .goingAway, reason: nil)
        task = nil
        attached = false
        connect()
    }

    /// `endSession` is reserved for an explicit user close. Transport swaps
    /// and retry attempts leave it false so the relay's reconnect grace works.
    func close(endSession: Bool = false) {
        guard !stopped else { return }
        stopped = true
        reconnectTask?.cancel()
        reconnectTask = nil
        heartbeatTask?.cancel()
        heartbeatTask = nil
        generation += 1 // invalidate receive/send callbacks from this task
        attached = false

        guard let current = task else { return }
        task = nil
        if endSession, let payload = Self.encodedFrame(Self.sessionCloseFrame()) {
            current.send(.string(payload)) { _ in
                current.cancel(with: .normalClosure, reason: nil)
            }
        } else {
            current.cancel(with: .normalClosure, reason: nil)
        }
    }

    // MARK: - Testable protocol primitives

    static func helloFrame(
        userToken: String,
        sessionId: String,
        clientId: String,
        lastSeq: Int
    ) -> [String: Any] {
        [
            "type": "hello",
            "token": userToken,
            "session_id": sessionId,
            "client_id": clientId,
            "client_name": "iphone",
            "last_seq": lastSeq,
        ]
    }

    static func sessionCloseFrame() -> [String: Any] {
        ["type": "session-close"]
    }

    static func turnFrame(
        input: String,
        clientMessageId: String,
        model: String = "",
        effort: String = "",
        permissions: String = "",
        images: [PendingImage] = []
    ) -> [String: Any] {
        var frame: [String: Any] = [
            "type": "turn-start",
            "input": input,
            "client_message_id": clientMessageId,
        ]
        if !model.isEmpty { frame["model"] = model }
        if !effort.isEmpty, effort != "none" { frame["effort"] = effort }
        if !permissions.isEmpty { frame["permissions"] = permissions }
        if !images.isEmpty { frame["images"] = imagePayloads(images) }
        return frame
    }

    static func slashFrame(command: String, args: String = "") -> [String: Any] {
        var frame: [String: Any] = [
            "type": "slash-command",
            "command": command,
        ]
        if !args.isEmpty { frame["args"] = args }
        return frame
    }

    private static func imagePayloads(_ images: [PendingImage]) -> [[String: String]] {
        images.map {
            ["mime": $0.mime, "data": $0.data.base64EncodedString()]
        }
    }

    /// Relay sequence numbers restart after a relay process restart, so this
    /// deliberately assigns the last value seen instead of taking a maximum.
    static func updatedReplayCursor(current: Int, frame: [String: Any]) -> Int {
        if frame["type"] as? String == "attached", let replayFrom = integer(frame["replay_from"]) {
            return replayFrom
        }
        return integer(frame["seq"]) ?? current
    }

    static func retryDelayMilliseconds(attempt: Int, jitter: Int? = nil) -> Int {
        let base = min(30_000, 1_000 << min(max(attempt, 0), 5))
        let ceiling = min(1_000, base / 4)
        return base + min(max(jitter ?? Int.random(in: 0...ceiling), 0), ceiling)
    }

    static func encodedFrame(_ object: [String: Any]) -> String? {
        guard JSONSerialization.isValidJSONObject(object),
              let data = try? JSONSerialization.data(withJSONObject: object) else {
            return nil
        }
        return String(data: data, encoding: .utf8)
    }

    // MARK: - Transport

    private func connect() {
        guard !stopped else { return }
        reconnectTask?.cancel()
        reconnectTask = nil
        heartbeatTask?.cancel()
        heartbeatTask = nil
        task?.cancel(with: .goingAway, reason: nil)

        generation += 1
        let currentGeneration = generation
        droppedGeneration = nil
        attached = false
        lastMessageAt = Date()
        onState(.connecting)

        let current = URLSession.shared.webSocketTask(with: url)
        current.maximumMessageSize = Self.maximumMessageBytes
        task = current
        current.resume()
        _ = sendRaw(
            Self.helloFrame(
                userToken: userToken,
                sessionId: sessionId,
                clientId: clientId,
                lastSeq: lastSeq
            ),
            over: current,
            generation: currentGeneration
        )
        receive(over: current, generation: currentGeneration)
    }

    private func send(_ object: [String: Any]) -> Bool {
        guard !stopped, attached, let current = task, current.state == .running else {
            return false
        }
        return sendRaw(object, over: current, generation: generation)
    }

    private func sendRaw(
        _ object: [String: Any],
        over current: URLSessionWebSocketTask,
        generation currentGeneration: Int
    ) -> Bool {
        guard let payload = Self.encodedFrame(object) else { return false }
        current.send(.string(payload)) { [weak self] error in
            guard let error else { return }
            Task { @MainActor in
                self?.handleDrop(error.localizedDescription, generation: currentGeneration)
            }
        }
        return true
    }

    private func receive(over current: URLSessionWebSocketTask, generation currentGeneration: Int) {
        current.receive { [weak self] result in
            Task { @MainActor in
                guard let self, currentGeneration == self.generation, !self.stopped else { return }
                switch result {
                case let .success(message):
                    self.handle(message: message, generation: currentGeneration)
                case let .failure(error):
                    self.handleDrop(error.localizedDescription, generation: currentGeneration)
                }
            }
        }
    }

    private func handle(message: URLSessionWebSocketTask.Message, generation currentGeneration: Int) {
        lastMessageAt = Date()
        guard let frame = Self.decode(message: message) else {
            if let current = task {
                receive(over: current, generation: currentGeneration)
            }
            return
        }

        lastSeq = Self.updatedReplayCursor(current: lastSeq, frame: frame)
        let type = frame["type"] as? String
        if type == "attached" {
            attached = true
            reconnectAttempt = 0
            startHeartbeat(for: currentGeneration)
        }

        let fatal = Self.boolean(frame["fatal"]) == true
        let terminal = fatal || type == "session-closed"
        onFrame(frame)
        if terminal {
            stop(reason: (frame["error"] as? String) ?? "Session closed")
            return
        }
        if let current = task {
            receive(over: current, generation: currentGeneration)
        }
    }

    private func handleDrop(_ reason: String, generation currentGeneration: Int) {
        guard currentGeneration == generation,
              !stopped,
              droppedGeneration != currentGeneration else {
            return
        }
        droppedGeneration = currentGeneration
        attached = false
        heartbeatTask?.cancel()
        heartbeatTask = nil
        task?.cancel(with: .goingAway, reason: nil)
        task = nil

        let delay = Self.retryDelayMilliseconds(attempt: reconnectAttempt)
        reconnectAttempt += 1
        onState(.reconnecting("Reconnecting in \((delay + 999) / 1000)s — \(reason)"))
        reconnectTask = Task { @MainActor [weak self] in
            do {
                try await Task.sleep(nanoseconds: UInt64(delay) * 1_000_000)
            } catch {
                return
            }
            guard let self, !self.stopped, currentGeneration == self.generation else { return }
            self.connect()
        }
    }

    private func startHeartbeat(for currentGeneration: Int) {
        heartbeatTask?.cancel()
        heartbeatTask = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                do {
                    try await Task.sleep(nanoseconds: Self.heartbeatIntervalNs)
                } catch {
                    return
                }
                guard let self,
                      !self.stopped,
                      self.attached,
                      currentGeneration == self.generation,
                      let current = self.task else {
                    return
                }
                if Date().timeIntervalSince(self.lastMessageAt) > Self.heartbeatStaleSeconds {
                    self.handleDrop("heartbeat timeout", generation: currentGeneration)
                    return
                }
                _ = self.sendRaw(
                    ["type": "ping", "ts": Int64(Date().timeIntervalSince1970 * 1_000)],
                    over: current,
                    generation: currentGeneration
                )
            }
        }
    }

    private func stop(reason: String) {
        guard !stopped else { return }
        stopped = true
        reconnectTask?.cancel()
        reconnectTask = nil
        heartbeatTask?.cancel()
        heartbeatTask = nil
        attached = false
        generation += 1
        task?.cancel(with: .normalClosure, reason: nil)
        task = nil
        onState(.stopped(reason))
    }

    static func stableClientId(defaults: UserDefaults = .standard) -> String {
        if let existing = defaults.string(forKey: clientIdKey), !existing.isEmpty {
            return existing
        }
        let id = "iphone-\(UUID().uuidString.lowercased())"
        defaults.set(id, forKey: clientIdKey)
        return id
    }

    private static func decode(message: URLSessionWebSocketTask.Message) -> [String: Any]? {
        let data: Data?
        switch message {
        case let .string(text):
            data = text.data(using: .utf8)
        case let .data(bytes):
            data = bytes
        @unknown default:
            data = nil
        }
        guard let data,
              let object = try? JSONSerialization.jsonObject(with: data),
              let frame = object as? [String: Any] else {
            return nil
        }
        return frame
    }

    private static func webSocketURL(baseURL: String) -> URL? {
        guard var components = RelayClient.validatedBaseComponents(baseURL: baseURL) else {
            return nil
        }
        components.scheme = components.scheme == "https" ? "wss" : "ws"
        components.path = RelayClient.joinedPath(
            basePath: components.path,
            endpoint: "/ws/client"
        )
        components.query = nil
        components.fragment = nil
        return components.url
    }

    private static func integer(_ value: Any?) -> Int? {
        if let value = value as? Int { return value }
        if let value = value as? NSNumber { return value.intValue }
        if let value = value as? String { return Int(value) }
        return nil
    }

    private static func boolean(_ value: Any?) -> Bool? {
        if let value = value as? Bool { return value }
        if let value = value as? NSNumber { return value.boolValue }
        if let value = value as? String { return Bool(value) }
        return nil
    }
}
