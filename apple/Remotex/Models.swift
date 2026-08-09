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


// Telemetry: every numeric field is optional because the daemon omits what
// it can't measure (no GPU block on non-NVIDIA hosts, no temperature inside
// containers).
struct CpuTelemetry: Decodable {
    let percent: Double?
    let cores: Int?
    let tempC: Double?

    enum CodingKeys: String, CodingKey {
        case percent, cores
        case tempC = "temp_c"
    }
}

struct MemoryTelemetry: Decodable {
    let usedBytes: Int64?
    let totalBytes: Int64?
    let percent: Double?

    enum CodingKeys: String, CodingKey {
        case usedBytes = "used_bytes"
        case totalBytes = "total_bytes"
        case percent
    }
}

struct GpuTelemetry: Decodable, Identifiable {
    let name: String?
    let percent: Double?
    let memUsedMb: Double?
    let memTotalMb: Double?

    var id: String { name ?? "gpu" }

    enum CodingKeys: String, CodingKey {
        case name, percent
        case memUsedMb = "mem_used_mb"
        case memTotalMb = "mem_total_mb"
    }
}

struct NetworkTelemetry: Decodable {
    let upBps: Int64?
    let downBps: Int64?

    enum CodingKeys: String, CodingKey {
        case upBps = "up_bps"
        case downBps = "down_bps"
    }
}

struct HostTelemetryData: Decodable {
    let cpu: CpuTelemetry?
    let memory: MemoryTelemetry?
    let gpus: [GpuTelemetry]?
    let gpu: GpuTelemetry?
    let network: NetworkTelemetry?
    let uptimeS: Int64?
    let loadAvg: [Double]?

    enum CodingKeys: String, CodingKey {
        case cpu, memory, gpus, gpu, network
        case uptimeS = "uptime_s"
        case loadAvg = "load_avg"
    }
}

struct HostTelemetryResponse: Decodable {
    let data: HostTelemetryData?
}

struct FsEntry: Decodable, Identifiable {
    let fileName: String
    let isDirectory: Bool

    var id: String { fileName }

    enum CodingKeys: String, CodingKey {
        case fileName = "fileName"
        case isDirectory = "isDirectory"
    }
}

struct FsListResponse: Decodable {
    let path: String?
    let entries: [FsEntry]
}
