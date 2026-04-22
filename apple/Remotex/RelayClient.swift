import Foundation

enum RelayClientError: LocalizedError {
    case invalidURL
    case badStatus(Int, String)

    var errorDescription: String? {
        switch self {
        case .invalidURL:
            return "Invalid relay URL"
        case let .badStatus(code, body):
            return "Relay returned \(code): \(body)"
        }
    }
}

final class RelayClient {
    private let decoder: JSONDecoder

    init() {
        decoder = JSONDecoder()
    }

    func listHosts(baseURL: String, userToken: String) async throws -> [Host] {
        var request = URLRequest(url: try url(baseURL: baseURL, path: "/api/hosts"))
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
        var request = URLRequest(url: try url(baseURL: baseURL, path: "/api/sessions"))
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

    func searchChats(baseURL: String, userToken: String, query: String, limit: Int = 20) async throws -> [SearchResult] {
        var components = URLComponents(url: try url(baseURL: baseURL, path: "/api/search"), resolvingAgainstBaseURL: false)
        components?.queryItems = [
            URLQueryItem(name: "q", value: query),
            URLQueryItem(name: "limit", value: String(limit)),
        ]
        guard let searchURL = components?.url else {
            throw RelayClientError.invalidURL
        }
        var request = URLRequest(url: searchURL)
        request.httpMethod = "GET"
        request.setValue("Bearer \(userToken)", forHTTPHeaderField: "Authorization")

        let data = try await data(for: request)
        return try decoder.decode(SearchResponse.self, from: data).results
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

    private func url(baseURL: String, path: String) throws -> URL {
        guard var components = URLComponents(string: baseURL.trimmingCharacters(in: .whitespacesAndNewlines)) else {
            throw RelayClientError.invalidURL
        }
        components.path = path
        components.query = nil
        guard let url = components.url else {
            throw RelayClientError.invalidURL
        }
        return url
    }
}
