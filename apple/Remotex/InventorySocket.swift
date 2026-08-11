import Foundation

enum InventorySocketState: Equatable {
    case connected
    case disconnected(String)
    case fatal(String)
}

/// Authenticated invalidation channel. REST remains the source of truth;
/// frames only tell the view model which inventory to refresh.
@MainActor
final class InventorySocket {
    private static let heartbeatIntervalNs: UInt64 = 20_000_000_000
    private static let heartbeatStaleSeconds: TimeInterval = 70
    private static let readyTimeoutNs: UInt64 = 15_000_000_000
    static let maximumMessageBytes = SessionSocket.maximumMessageBytes

    private let url: URL
    private let userToken: String
    private let clientId: String
    private let onFrame: @MainActor ([String: Any]) -> Void
    private let onState: @MainActor (InventorySocketState) -> Void

    private var task: URLSessionWebSocketTask?
    private var reconnectTask: Task<Void, Never>?
    private var heartbeatTask: Task<Void, Never>?
    private var readyTask: Task<Void, Never>?
    private var generation = 0
    private var reconnectAttempt = 0
    private var stopped = false
    private var ready = false
    private var lastMessageAt = Date()

    init(
        baseURL: String,
        userToken: String,
        clientId: String? = nil,
        onFrame: @escaping @MainActor ([String: Any]) -> Void,
        onState: @escaping @MainActor (InventorySocketState) -> Void
    ) throws {
        guard let url = Self.webSocketURL(baseURL: baseURL) else {
            throw RelayClientError.invalidURL
        }
        self.url = url
        self.userToken = userToken
        self.clientId = clientId ?? "inventory-\(SessionSocket.stableClientId())"
        self.onFrame = onFrame
        self.onState = onState
        connect()
    }

    func resumeAfterForeground() {
        guard !stopped else { return }
        if ready, Date().timeIntervalSince(lastMessageAt) < Self.heartbeatStaleSeconds / 2 {
            startHeartbeat(for: generation)
            return
        }
        reconnectTask?.cancel()
        reconnectTask = nil
        reconnectAttempt = 0
        dropCurrentTask()
        connect()
    }

    func close() {
        guard !stopped else { return }
        stopped = true
        generation += 1
        reconnectTask?.cancel()
        reconnectTask = nil
        heartbeatTask?.cancel()
        heartbeatTask = nil
        readyTask?.cancel()
        readyTask = nil
        dropCurrentTask()
    }

    static func helloFrame(userToken: String, clientId: String) -> [String: Any] {
        [
            "type": "hello",
            "token": userToken,
            "client_id": clientId,
            "client_name": "iphone",
        ]
    }

    static func webSocketURL(baseURL: String) -> URL? {
        guard var components = RelayClient.validatedBaseComponents(baseURL: baseURL) else {
            return nil
        }
        components.scheme = components.scheme == "https" ? "wss" : "ws"
        components.path = RelayClient.joinedPath(
            basePath: components.path,
            endpoint: "/ws/inventory"
        )
        components.query = nil
        components.fragment = nil
        return components.url
    }

    private func connect() {
        guard !stopped else { return }
        generation += 1
        let currentGeneration = generation
        ready = false
        lastMessageAt = Date()
        dropCurrentTask()

        let current = URLSession.shared.webSocketTask(with: url)
        current.maximumMessageSize = Self.maximumMessageBytes
        task = current
        current.resume()
        sendRaw(
            Self.helloFrame(userToken: userToken, clientId: clientId),
            over: current,
            generation: currentGeneration
        )
        receive(over: current, generation: currentGeneration)
        startReadyTimeout(for: currentGeneration)
    }

    private func sendRaw(
        _ frame: [String: Any],
        over current: URLSessionWebSocketTask,
        generation currentGeneration: Int
    ) {
        guard let text = SessionSocket.encodedFrame(frame) else { return }
        current.send(.string(text)) { [weak self] error in
            guard let error else { return }
            Task { @MainActor in
                self?.handleDrop(error.localizedDescription, generation: currentGeneration)
            }
        }
    }

    private func receive(over current: URLSessionWebSocketTask, generation currentGeneration: Int) {
        current.receive { [weak self] result in
            Task { @MainActor in
                guard let self,
                      !self.stopped,
                      currentGeneration == self.generation else { return }
                switch result {
                case let .success(message):
                    self.handle(message, generation: currentGeneration)
                case let .failure(error):
                    self.handleDrop(error.localizedDescription, generation: currentGeneration)
                }
            }
        }
    }

    private func handle(
        _ message: URLSessionWebSocketTask.Message,
        generation currentGeneration: Int
    ) {
        lastMessageAt = Date()
        guard let frame = Self.decode(message) else {
            if let current = task {
                receive(over: current, generation: currentGeneration)
            }
            return
        }
        let type = frame["type"] as? String
        if type == "pong" {
            if let current = task {
                receive(over: current, generation: currentGeneration)
            }
            return
        }
        if type == "inventory-ready" {
            ready = true
            reconnectAttempt = 0
            readyTask?.cancel()
            readyTask = nil
            onState(.connected)
            startHeartbeat(for: currentGeneration)
        }
        let fatal = Self.bool(frame["fatal"])
        onFrame(frame)
        if fatal {
            stopFatal((frame["error"] as? String) ?? "Inventory authentication failed.")
            return
        }
        if let current = task {
            receive(over: current, generation: currentGeneration)
        }
    }

    private func handleDrop(_ reason: String, generation currentGeneration: Int) {
        guard !stopped, currentGeneration == generation else { return }
        ready = false
        heartbeatTask?.cancel()
        heartbeatTask = nil
        readyTask?.cancel()
        readyTask = nil
        dropCurrentTask()

        let delay = SessionSocket.retryDelayMilliseconds(attempt: reconnectAttempt)
        reconnectAttempt += 1
        onState(.disconnected("Inventory reconnecting in \((delay + 999) / 1000)s — \(reason)"))
        generation += 1
        let droppedGeneration = generation
        reconnectTask?.cancel()
        reconnectTask = Task { @MainActor [weak self] in
            do {
                try await Task.sleep(nanoseconds: UInt64(delay) * 1_000_000)
            } catch {
                return
            }
            guard let self,
                  !self.stopped,
                  droppedGeneration == self.generation else { return }
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
                      self.ready,
                      !self.stopped,
                      currentGeneration == self.generation,
                      let current = self.task else { return }
                if Date().timeIntervalSince(self.lastMessageAt) > Self.heartbeatStaleSeconds {
                    self.handleDrop("heartbeat timeout", generation: currentGeneration)
                    return
                }
                self.sendRaw(
                    ["type": "ping", "ts": Int64(Date().timeIntervalSince1970 * 1_000)],
                    over: current,
                    generation: currentGeneration
                )
            }
        }
    }

    private func startReadyTimeout(for currentGeneration: Int) {
        readyTask?.cancel()
        readyTask = Task { @MainActor [weak self] in
            do {
                try await Task.sleep(nanoseconds: Self.readyTimeoutNs)
            } catch {
                return
            }
            guard let self,
                  !self.ready,
                  !self.stopped,
                  currentGeneration == self.generation else { return }
            self.handleDrop("inventory-ready timeout", generation: currentGeneration)
        }
    }

    private func stopFatal(_ reason: String) {
        guard !stopped else { return }
        stopped = true
        generation += 1
        reconnectTask?.cancel()
        heartbeatTask?.cancel()
        readyTask?.cancel()
        reconnectTask = nil
        heartbeatTask = nil
        readyTask = nil
        dropCurrentTask()
        onState(.fatal(reason))
    }

    private func dropCurrentTask() {
        task?.cancel(with: .normalClosure, reason: nil)
        task = nil
    }

    private static func decode(
        _ message: URLSessionWebSocketTask.Message
    ) -> [String: Any]? {
        let data: Data
        switch message {
        case let .string(text):
            guard let encoded = text.data(using: .utf8) else { return nil }
            data = encoded
        case let .data(bytes):
            data = bytes
        @unknown default:
            return nil
        }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    private static func bool(_ value: Any?) -> Bool {
        if let value = value as? Bool { return value }
        if let value = value as? NSNumber { return value.boolValue }
        if let value = value as? String { return Bool(value) ?? false }
        return false
    }
}
