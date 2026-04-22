import Foundation

struct Host: Identifiable, Decodable, Equatable {
    let id: String
    let nickname: String
    let hostname: String?
    let platform: String?
    let online: Bool
    let lastSeen: Int?

    enum CodingKeys: String, CodingKey {
        case id
        case nickname
        case hostname
        case platform
        case online
        case lastSeen = "last_seen"
    }
}

struct HostsResponse: Decodable {
    let hosts: [Host]
}

struct OpenSessionResponse: Decodable {
    let sessionId: String

    enum CodingKeys: String, CodingKey {
        case sessionId = "session_id"
    }
}

struct SessionInfo: Equatable {
    let sessionId: String
    let hostId: String
    var model: String?
    var cwd: String?
    var threadId: String?
}

enum ConnectionStatus: String {
    case idle
    case loading
    case opening
    case connecting
    case connected
    case disconnected
    case error
}

enum StreamRole: String {
    case user
    case reasoning
    case tool
    case agent
    case system
}

struct StreamItem: Identifiable, Equatable {
    let id: String
    var role: StreamRole
    var title: String
    var text: String
    var detail: String = ""
    var completed: Bool = false
}

