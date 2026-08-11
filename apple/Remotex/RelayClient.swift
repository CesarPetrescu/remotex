import Foundation

enum RelayClientError: LocalizedError {
    case invalidURL
    case badStatus(Int, String)
    case invalidResponse(String)
    case fileTooLarge(Int)
    case invalidFileName

    var errorDescription: String? {
        switch self {
        case .invalidURL:
            return "Invalid relay URL"
        case let .badStatus(code, body):
            return "Relay returned \(code): \(body)"
        case let .invalidResponse(message):
            return message
        case let .fileTooLarge(maxBytes):
            return "File must be \(maxBytes / 1024 / 1024) MB or smaller."
        case .invalidFileName:
            return "Use a single file or folder name without slashes."
        }
    }
}

final class RelayClient {
    private let decoder: JSONDecoder

    init() {
        decoder = JSONDecoder()
    }

    func listHosts(baseURL: String, userToken: String) async throws -> [Host] {
        var request = URLRequest(url: try Self.makeURL(baseURL: baseURL, path: "/api/hosts"))
        request.httpMethod = "GET"
        request.setValue("Bearer \(userToken)", forHTTPHeaderField: "Authorization")

        let data = try await data(for: request)
        return try decoder.decode(HostsResponse.self, from: data).hosts
    }

    func openSession(
        baseURL: String,
        userToken: String,
        hostId: String,
        threadId: String? = nil,
        cwd: String? = nil
    ) async throws -> String {
        var request = URLRequest(url: try Self.makeURL(baseURL: baseURL, path: "/api/sessions"))
        request.httpMethod = "POST"
        request.setValue("Bearer \(userToken)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        var body: [String: Any] = ["host_id": hostId]
        if let threadId, !threadId.isEmpty {
            body["thread_id"] = threadId
        }
        if let cwd, !cwd.isEmpty {
            body["cwd"] = cwd
        }
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        let data = try await data(for: request)
        return try decoder.decode(OpenSessionResponse.self, from: data).sessionId
    }

    func listThreads(
        baseURL: String,
        userToken: String,
        hostId: String,
        limit: Int = 25
    ) async throws -> [ThreadInfo] {
        var request = URLRequest(
            url: try Self.makeURL(
                baseURL: baseURL,
                path: "/api/hosts/\(hostId)/threads",
                queryItems: [URLQueryItem(name: "limit", value: String(limit))]
            )
        )
        request.httpMethod = "GET"
        request.setValue("Bearer \(userToken)", forHTTPHeaderField: "Authorization")
        let data = try await data(for: request)
        return try decoder.decode(ThreadsResponse.self, from: data).threads
    }

    // Disk-backed on the daemon (never codex) — safe to fire on every tap.
    func threadPreview(
        baseURL: String,
        userToken: String,
        hostId: String,
        threadId: String
    ) async throws -> PreviewResponse {
        var request = URLRequest(
            url: try Self.makeURL(
                baseURL: baseURL,
                path: "/api/hosts/\(hostId)/threads/\(threadId)/preview",
                queryItems: [URLQueryItem(name: "turns", value: "2")]
            )
        )
        request.httpMethod = "GET"
        request.setValue("Bearer \(userToken)", forHTTPHeaderField: "Authorization")
        let data = try await data(for: request)
        return try decoder.decode(PreviewResponse.self, from: data)
    }

    func listHostModels(
        baseURL: String,
        userToken: String,
        hostId: String
    ) async throws -> [ModelOption] {
        var request = URLRequest(
            url: try Self.makeURL(baseURL: baseURL, path: "/api/hosts/\(hostId)/models")
        )
        request.httpMethod = "GET"
        request.setValue("Bearer \(userToken)", forHTTPHeaderField: "Authorization")
        let data = try await data(for: request)
        return try decoder.decode(ModelsResponse.self, from: data).models
    }

    func hostTelemetry(
        baseURL: String,
        userToken: String,
        hostId: String
    ) async throws -> HostTelemetryData? {
        var request = URLRequest(
            url: try Self.makeURL(baseURL: baseURL, path: "/api/hosts/\(hostId)/telemetry")
        )
        request.httpMethod = "GET"
        request.setValue("Bearer \(userToken)", forHTTPHeaderField: "Authorization")
        let data = try await data(for: request)
        return try decoder.decode(HostTelemetryResponse.self, from: data).data
    }

    func readDirectory(
        baseURL: String,
        userToken: String,
        hostId: String,
        path: String
    ) async throws -> FsListResponse {
        var request = URLRequest(
            url: try Self.makeURL(
                baseURL: baseURL,
                path: "/api/hosts/\(hostId)/fs",
                queryItems: [URLQueryItem(name: "path", value: path)]
            )
        )
        request.httpMethod = "GET"
        request.setValue("Bearer \(userToken)", forHTTPHeaderField: "Authorization")
        let data = try await data(for: request)
        return try decoder.decode(FsListResponse.self, from: data)
    }

    func createDirectory(
        baseURL: String,
        userToken: String,
        hostId: String,
        path: String,
        name: String
    ) async throws {
        let safeName = try Self.validatedFileName(name)
        try await sendJSON(
            baseURL: baseURL,
            userToken: userToken,
            path: "/api/hosts/\(hostId)/fs/mkdir",
            body: ["path": path, "name": safeName]
        )
    }

    func readFile(
        baseURL: String,
        userToken: String,
        hostId: String,
        path: String
    ) async throws -> DownloadedFile {
        var request = URLRequest(
            url: try Self.makeURL(
                baseURL: baseURL,
                path: "/api/hosts/\(hostId)/fs/read",
                queryItems: [URLQueryItem(name: "path", value: path)]
            )
        )
        request.httpMethod = "GET"
        request.setValue("Bearer \(userToken)", forHTTPHeaderField: "Authorization")
        let data = try await data(for: request)
        let response = try decoder.decode(FsReadResponse.self, from: data)
        guard let bytes = Data(base64Encoded: response.base64) else {
            throw RelayClientError.invalidResponse("Relay returned invalid file data.")
        }
        try Self.validateFileSize(response.size ?? bytes.count)
        try Self.validateFileSize(bytes.count)
        return DownloadedFile(
            name: response.name?.isEmpty == false ? response.name! : URL(fileURLWithPath: path).lastPathComponent,
            mime: response.mime ?? "application/octet-stream",
            data: bytes
        )
    }

    func deleteFile(
        baseURL: String,
        userToken: String,
        hostId: String,
        path: String
    ) async throws {
        try await sendJSON(
            baseURL: baseURL,
            userToken: userToken,
            path: "/api/hosts/\(hostId)/fs/delete",
            body: ["path": path]
        )
    }

    func renameFile(
        baseURL: String,
        userToken: String,
        hostId: String,
        from: String,
        to: String
    ) async throws {
        try await sendJSON(
            baseURL: baseURL,
            userToken: userToken,
            path: "/api/hosts/\(hostId)/fs/rename",
            body: ["from": from, "to": to]
        )
    }

    func uploadFile(
        baseURL: String,
        userToken: String,
        hostId: String,
        directory: String,
        fileName: String,
        bytes: Data
    ) async throws {
        try Self.validateFileSize(bytes.count)
        let safeName = try Self.validatedFileName(fileName)
        let boundary = "remotex-\(UUID().uuidString)"
        var request = URLRequest(
            url: try Self.makeURL(
                baseURL: baseURL,
                path: "/api/hosts/\(hostId)/fs/upload"
            )
        )
        request.httpMethod = "POST"
        request.setValue("Bearer \(userToken)", forHTTPHeaderField: "Authorization")
        request.setValue(
            "multipart/form-data; boundary=\(boundary)",
            forHTTPHeaderField: "Content-Type"
        )
        request.httpBody = Self.multipartBody(
            directory: directory,
            fileName: safeName,
            bytes: bytes,
            boundary: boundary
        )
        _ = try await data(for: request)
    }

    private func sendJSON(
        baseURL: String,
        userToken: String,
        path: String,
        body: [String: String]
    ) async throws {
        var request = URLRequest(url: try Self.makeURL(baseURL: baseURL, path: path))
        request.httpMethod = "POST"
        request.setValue("Bearer \(userToken)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        _ = try await data(for: request)
    }

    private func data(for request: URLRequest) async throws -> Data {
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw RelayClientError.badStatus(0, "missing HTTP response")
        }
        guard (200..<300).contains(http.statusCode) else {
            let body = String(data: data, encoding: .utf8) ?? ""
            throw RelayClientError.badStatus(http.statusCode, body)
        }
        return data
    }

    static func makeURL(
        baseURL: String,
        path: String,
        queryItems: [URLQueryItem] = []
    ) throws -> URL {
        guard var components = validatedBaseComponents(baseURL: baseURL) else {
            throw RelayClientError.invalidURL
        }
        components.path = joinedPath(basePath: components.path, endpoint: path)
        components.queryItems = queryItems.isEmpty ? nil : queryItems
        components.fragment = nil
        guard let url = components.url else {
            throw RelayClientError.invalidURL
        }
        return url
    }

    static func validatedBaseComponents(
        baseURL: String
    ) -> URLComponents? {
        validatedBaseComponents(
            baseURL: baseURL,
            allowInsecure: allowsInsecureRelay
        )
    }

    static func validatedBaseComponents(
        baseURL: String,
        allowInsecure: Bool
    ) -> URLComponents? {
        guard var components = URLComponents(
            string: baseURL.trimmingCharacters(in: .whitespacesAndNewlines)
        ), let rawScheme = components.scheme,
           let rawHost = components.host,
           !rawHost.isEmpty,
           components.user == nil,
           components.password == nil else {
            return nil
        }
        let scheme = rawScheme.lowercased()
        guard scheme == "https" || (allowInsecure && scheme == "http") else {
            return nil
        }
        components.scheme = scheme
        components.host = rawHost.lowercased()
        guard components.url != nil else { return nil }
        return components
    }

    /// Tokens belong to the canonical relay base, including a reverse-proxy
    /// path prefix. `/team-a` and `/team-b` on one origin are distinct relays.
    static func canonicalRelayScope(_ baseURL: String) -> String? {
        guard var components = validatedBaseComponents(
            baseURL: baseURL,
            allowInsecure: true
        ) else { return nil }
        let scheme = components.scheme ?? ""
        if (scheme == "http" && components.port == 80)
            || (scheme == "https" && components.port == 443) {
            components.port = nil
        }
        components.path = normalizedBasePath(components.path)
        components.query = nil
        components.fragment = nil
        return components.string
    }

    static func joinedPath(basePath: String, endpoint: String) -> String {
        let prefix = normalizedBasePath(basePath)
        let suffix = endpoint.hasPrefix("/") ? endpoint : "/\(endpoint)"
        return prefix + suffix
    }

    static func validateFileSize(_ size: Int) throws {
        guard size <= RemotexViewModel.maxAttachmentBytes else {
            throw RelayClientError.fileTooLarge(RemotexViewModel.maxAttachmentBytes)
        }
    }

    static func validatedFileName(_ raw: String) throws -> String {
        let name = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty,
              name != ".",
              name != "..",
              !name.contains("/"),
              !name.contains("\\"),
              !name.contains("\""),
              !name.contains("\r"),
              !name.contains("\n") else {
            throw RelayClientError.invalidFileName
        }
        return name
    }

    static func multipartBody(
        directory: String,
        fileName: String,
        bytes: Data,
        boundary: String
    ) -> Data {
        var body = Data()
        body.append("--\(boundary)\r\nContent-Disposition: form-data; name=\"path\"\r\n\r\n\(directory)\r\n".data(using: .utf8)!)
        body.append("--\(boundary)\r\nContent-Disposition: form-data; name=\"file\"; filename=\"\(fileName)\"\r\nContent-Type: application/octet-stream\r\n\r\n".data(using: .utf8)!)
        body.append(bytes)
        body.append("\r\n--\(boundary)--\r\n".data(using: .utf8)!)
        return body
    }

    private static func normalizedBasePath(_ path: String) -> String {
        var normalized = path
        while normalized.count > 1, normalized.hasSuffix("/") {
            normalized.removeLast()
        }
        return normalized == "/" ? "" : normalized
    }

    static var allowsInsecureRelay: Bool {
#if DEBUG
        true
#else
        false
#endif
    }
}
