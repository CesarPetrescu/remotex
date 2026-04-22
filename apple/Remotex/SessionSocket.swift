import Foundation

enum SessionSocketError: LocalizedError {
    case invalidURL

    var errorDescription: String? {
        switch self {
        case .invalidURL:
            return "Invalid WebSocket URL"
        }
    }
}

final class SessionSocket {
    private let task: URLSessionWebSocketTask
    private let onFrame: ([String: Any]) -> Void
    private let onClose: (String) -> Void

    init(
        baseURL: String,
        userToken: String,
        sessionId: String,
        onFrame: @escaping ([String: Any]) -> Void,
        onClose: @escaping (String) -> Void
    ) throws {
        guard let url = Self.webSocketURL(baseURL: baseURL) else {
            throw SessionSocketError.invalidURL
        }
        self.task = URLSession.shared.webSocketTask(with: url)
        self.onFrame = onFrame
        self.onClose = onClose

        task.resume()
        send([
            "type": "hello",
            "token": userToken,
            "session_id": sessionId,
        ])
        receive()
    }

    func sendTurn(_ input: String) {
        send([
            "type": "turn-start",
            "input": input,
        ])
    }

    func close() {
        task.cancel(with: .normalClosure, reason: nil)
    }

    private func send(_ object: [String: Any]) {
        guard JSONSerialization.isValidJSONObject(object),
              let data = try? JSONSerialization.data(withJSONObject: object),
              let text = String(data: data, encoding: .utf8) else {
            return
        }
        task.send(.string(text)) { [weak self] error in
            if let error {
                self?.onClose(error.localizedDescription)
            }
        }
    }

    private func receive() {
        task.receive { [weak self] result in
            guard let self else { return }
            switch result {
            case let .success(message):
                if let frame = Self.decode(message: message) {
                    onFrame(frame)
                }
                receive()
            case let .failure(error):
                onClose(error.localizedDescription)
            }
        }
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
        guard var components = URLComponents(string: baseURL.trimmingCharacters(in: .whitespacesAndNewlines)) else {
            return nil
        }
        components.scheme = components.scheme == "https" ? "wss" : "ws"
        components.path = "/ws/client"
        components.query = nil
        return components.url
    }
}

