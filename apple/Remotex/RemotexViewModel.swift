import Foundation
import Combine

@MainActor
final class RemotexViewModel: ObservableObject {
    @Published var relayURL: String {
        didSet { UserDefaults.standard.set(relayURL, forKey: Self.relayURLKey) }
    }
    @Published var userToken: String {
        didSet { UserDefaults.standard.set(userToken, forKey: Self.userTokenKey) }
    }
    @Published private(set) var hosts: [Host] = []
    @Published private(set) var selectedHost: Host?
    @Published private(set) var status: ConnectionStatus = .idle
    @Published private(set) var session: SessionInfo?
    @Published private(set) var stream: [StreamItem] = []
    @Published var searchQuery: String = ""
    @Published private(set) var searchResults: [SearchResult] = []
    @Published private(set) var searchLoading: Bool = false
    @Published var prompt: String = ""
    @Published var errorMessage: String?
    @Published private(set) var pending: Bool = false

    private static let relayURLKey = "remotex.relayURL"
    private static let userTokenKey = "remotex.userToken"

    private let client = RelayClient()
    private var socket: SessionSocket?

    init() {
        self.relayURL = UserDefaults.standard.string(forKey: Self.relayURLKey) ?? "http://127.0.0.1:8080"
        self.userToken = UserDefaults.standard.string(forKey: Self.userTokenKey) ?? "demo-user-token"
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

    func searchChats() {
        let query = searchQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else {
            searchResults = []
            return
        }
        searchLoading = true
        errorMessage = nil
        Task {
            do {
                searchResults = try await client.searchChats(
                    baseURL: relayURL,
                    userToken: userToken,
                    query: query
                )
                searchLoading = false
            } catch {
                searchLoading = false
                errorMessage = error.localizedDescription
            }
        }
    }

    func openSearchResult(_ result: SearchResult) {
        guard let threadId = result.threadId, !threadId.isEmpty else {
            errorMessage = "This result has no resumable Codex thread yet."
            return
        }
        let host = hosts.first { $0.id == result.hostId }
        if let host, !host.online {
            errorMessage = "\(host.nickname) is offline"
            return
        }
        openSession(hostId: result.hostId, host: host, threadId: threadId, cwd: result.cwd)
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
        guard !input.isEmpty, let socket else { return }
        prompt = ""
        pending = true
        stream.append(StreamItem(
            id: "user-\(UUID().uuidString.prefix(8))",
            role: .user,
            title: "You",
            text: input,
            completed: true
        ))
        socket.sendTurn(input)
    }

    func closeSession(clearSelectedHost: Bool = true) {
        socket?.close()
        socket = nil
        session = nil
        pending = false
        stream = []
        status = .idle
        if clearSelectedHost {
            selectedHost = nil
        }
    }

    private func handleClose(reason: String) {
        guard socket != nil else { return }
        socket = nil
        pending = false
        status = .disconnected
        appendSystem("Disconnected", reason)
    }

    private func handle(frame: [String: Any]) {
        switch frame.string("type") {
        case "attached":
            status = .connected
        case "session-closed":
            status = .disconnected
            pending = false
        case "session-event":
            guard let event = frame.dictionary("event"),
                  let kind = event.string("kind") else {
                return
            }
            handleSessionEvent(kind: kind, data: event.dictionary("data") ?? [:])
        case "error":
            status = .error
            errorMessage = frame.string("error") ?? "Relay error"
        default:
            break
        }
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

        case "turn-completed":
            pending = false

        case "approval-request":
            appendSystem("Approval needed", data.string("reason") ?? data.string("command") ?? "Codex is waiting for approval.")

        case "slash-ack":
            let command = data.string("command") ?? "slash"
            appendSystem("/\(command)", data.string("message") ?? data.string("error") ?? "ok")

        case "thread-status":
            appendSystem("Thread", data.string("status") ?? "")

        default:
            break
        }
    }

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
        stream.append(item)
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
