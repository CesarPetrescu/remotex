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
    case gap
}

struct StreamItem: Identifiable, Equatable {
    let id: String
    var role: StreamRole
    var title: String
    var text: String
    var detail: String = ""
    var completed: Bool = false
}

struct ApprovalPrompt: Identifiable, Equatable {
    let approvalId: String
    let kind: String?
    let reason: String?
    let command: String?
    let cwd: String?
    let decisions: [String]

    var id: String { approvalId }
}

struct UserInputOption: Identifiable, Equatable {
    let label: String
    let description: String

    var id: String { label }
}

struct UserInputQuestion: Identifiable, Equatable {
    let id: String
    let header: String
    let question: String
    let options: [UserInputOption]
}

struct UserInputPrompt: Identifiable, Equatable {
    let callId: String
    let questions: [UserInputQuestion]

    var id: String { callId }
}


struct ThreadInfo: Identifiable, Decodable, Equatable {
    let id: String
    let title: String?
    let titleIsGeneric: Bool?
    let preview: String?
    let cwd: String?
    let updatedAt: Int?

    enum CodingKeys: String, CodingKey {
        case id
        case title
        case titleIsGeneric = "title_is_generic"
        case preview
        case cwd
        case updatedAt = "updated_at"
    }

    var displayTitle: String {
        if let title, titleIsGeneric == false { return title }
        return preview?.isEmpty == false ? preview! : "(no preview)"
    }
}

struct ThreadsResponse: Decodable {
    let threads: [ThreadInfo]
}

struct PreviewTurn: Decodable {
    let role: String
    let text: String
}

struct PreviewResponse: Decodable {
    let available: Bool
    let turns: [PreviewTurn]
}

struct ModelOption: Identifiable, Decodable, Equatable {
    let id: String
    let label: String
    let hint: String?
    let efforts: [String]
}

struct ModelsResponse: Decodable {
    let models: [ModelOption]
}
