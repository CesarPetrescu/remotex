import Foundation
import XCTest
@testable import Remotex

@MainActor
final class CoreReliabilityTests: XCTestCase {
    private func queuedTurn(id: String, sending: Bool = false) -> QueuedTurn {
        QueuedTurn(
            id: id,
            clientMessageId: "msg-\(id)",
            text: id,
            model: "model",
            effort: "high",
            permissions: "default",
            images: [],
            sending: sending
        )
    }

    func testRelayTokenScopeCanonicalizesEquivalentProviderURLs() {
        XCTAssertEqual(
            RemotexViewModel.normalizedRelayScope(" HTTPS://Relay.Example:443/ "),
            "https://relay.example"
        )
        XCTAssertEqual(
            RemotexViewModel.normalizedRelayScope(
                "http://Relay.Example:80/work///?token=ignored#fragment"
            ),
            "http://relay.example/work"
        )
        XCTAssertNil(
            RemotexViewModel.normalizedRelayScope(
                "https://name:password@relay.example"
            )
        )
        XCTAssertNil(RemotexViewModel.normalizedRelayScope("relay.example"))
        XCTAssertNil(RemotexViewModel.normalizedRelayScope("ftp://relay.example"))
    }

    func testRelayTokenAccountsAreProviderSpecific() throws {
        let first = try XCTUnwrap(
            RemotexViewModel.tokenAccount(for: "https://relay-one.example/")
        )
        let equivalent = try XCTUnwrap(
            RemotexViewModel.tokenAccount(for: "HTTPS://RELAY-ONE.EXAMPLE:443")
        )
        let second = try XCTUnwrap(
            RemotexViewModel.tokenAccount(for: "https://relay-two.example")
        )
        XCTAssertEqual(first, equivalent)
        XCTAssertNotEqual(first, second)
        XCTAssertNotEqual(
            RemotexViewModel.tokenAccount(for: "https://relay-one.example/team-a"),
            RemotexViewModel.tokenAccount(for: "https://relay-one.example/team-b")
        )
    }

    func testReleasePolicyRejectsCleartextAndEmbeddedCredentials() {
        XCTAssertNotNil(RelayClient.validatedBaseComponents(
            baseURL: "https://relay.example",
            allowInsecure: false
        ))
        XCTAssertNil(RelayClient.validatedBaseComponents(
            baseURL: "http://relay.example",
            allowInsecure: false
        ))
        XCTAssertNotNil(RelayClient.validatedBaseComponents(
            baseURL: "http://192.168.1.20:8080",
            allowInsecure: true
        ))
        XCTAssertNil(RelayClient.validatedBaseComponents(
            baseURL: "https://token@relay.example",
            allowInsecure: false
        ))
        XCTAssertNil(RelayClient.validatedBaseComponents(
            baseURL: "wss://relay.example",
            allowInsecure: false
        ))
    }

    func testRESTQueryItemsAreNotEncodedIntoThePath() throws {
        let url = try RelayClient.makeURL(
            baseURL: "https://relay.example",
            path: "/api/hosts/host-1/threads",
            queryItems: [URLQueryItem(name: "limit", value: "25")]
        )
        let components = try XCTUnwrap(URLComponents(url: url, resolvingAgainstBaseURL: false))
        XCTAssertEqual(components.path, "/api/hosts/host-1/threads")
        XCTAssertEqual(components.queryItems?.count, 1)
        XCTAssertEqual(components.queryItems?.first?.name, "limit")
        XCTAssertEqual(components.queryItems?.first?.value, "25")
        XCTAssertFalse(url.absoluteString.contains("%3F"))
    }

    func testReverseProxyBasePathIsPreserved() throws {
        let url = try RelayClient.makeURL(
            baseURL: "https://relay.example/remotex/",
            path: "/api/hosts"
        )
        XCTAssertEqual(url.path, "/remotex/api/hosts")
        XCTAssertEqual(
            RelayClient.joinedPath(basePath: "/remotex/", endpoint: "/ws/client"),
            "/remotex/ws/client"
        )
    }

    func testFilesystemPathRoundTripsAsOneQueryValue() throws {
        let path = "/work/a folder/#notes?.swift"
        let url = try RelayClient.makeURL(
            baseURL: "https://relay.example",
            path: "/api/hosts/host-1/fs",
            queryItems: [URLQueryItem(name: "path", value: path)]
        )
        let components = try XCTUnwrap(URLComponents(url: url, resolvingAgainstBaseURL: false))
        XCTAssertEqual(components.path, "/api/hosts/host-1/fs")
        XCTAssertEqual(components.queryItems?.first?.value, path)
        XCTAssertFalse(url.absoluteString.contains("%252F"), "query values must not be double encoded")
    }

    func testHelloCarriesStableIdentityAndReplayCursor() {
        let frame = SessionSocket.helloFrame(
            userToken: "secret",
            sessionId: "sess_1",
            clientId: "iphone-fixed",
            lastSeq: 42
        )
        XCTAssertEqual(frame["type"] as? String, "hello")
        XCTAssertEqual(frame["client_id"] as? String, "iphone-fixed")
        XCTAssertEqual(frame["client_name"] as? String, "iphone")
        XCTAssertEqual(frame["last_seq"] as? Int, 42)
    }

    func testClientIdentityIsStableAcrossSocketInstances() throws {
        let suite = "remotex-tests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let first = SessionSocket.stableClientId(defaults: defaults)
        let second = SessionSocket.stableClientId(defaults: defaults)
        XCTAssertEqual(first, second)
        XCTAssertTrue(first.hasPrefix("iphone-"))
    }

    func testReplayCursorAssignsRatherThanTakingMaximum() {
        XCTAssertEqual(
            SessionSocket.updatedReplayCursor(
                current: 900,
                frame: ["type": "attached", "replay_from": 0]
            ),
            0,
            "a relay restart resets its sequence counter"
        )
        XCTAssertEqual(
            SessionSocket.updatedReplayCursor(current: 900, frame: ["seq": 3]),
            3
        )
        XCTAssertEqual(
            SessionSocket.updatedReplayCursor(current: 3, frame: ["type": "pong"]),
            3
        )
    }

    func testExplicitClosePayloadEndsTheBackendSession() throws {
        let payload = try XCTUnwrap(SessionSocket.encodedFrame(SessionSocket.sessionCloseFrame()))
        let data = try XCTUnwrap(payload.data(using: .utf8))
        let object = try XCTUnwrap(
            JSONSerialization.jsonObject(with: data) as? [String: Any]
        )
        XCTAssertEqual(object["type"] as? String, "session-close")
        XCTAssertEqual(object.count, 1)
    }

    func testReconnectBackoffIsCapped() {
        XCTAssertEqual(SessionSocket.retryDelayMilliseconds(attempt: 0, jitter: 0), 1_000)
        XCTAssertEqual(SessionSocket.retryDelayMilliseconds(attempt: 1, jitter: 0), 2_000)
        XCTAssertEqual(SessionSocket.retryDelayMilliseconds(attempt: 20, jitter: 0), 30_000)
        XCTAssertEqual(SessionSocket.retryDelayMilliseconds(attempt: 20, jitter: 50_000), 31_000)
    }

    func testSlashParserPreservesArgumentsAndNormalizesCommand() {
        XCTAssertEqual(
            RemotexViewModel.parseSlash("  /CD   /work/a folder  "),
            ParsedSlash(command: "cd", args: "/work/a folder")
        )
        XCTAssertEqual(
            RemotexViewModel.parseSlash("/compact"),
            ParsedSlash(command: "compact", args: "")
        )
        XCTAssertNil(RemotexViewModel.parseSlash("ask codex"))
        XCTAssertNil(RemotexViewModel.parseSlash("/"))
    }

    func testTurnFrameCarriesImageBytesAndSettings() throws {
        let image = PendingImage(
            id: UUID(uuidString: "00000000-0000-0000-0000-000000000001")!,
            data: Data([0, 1, 2]),
            mime: "image/png",
            label: "tiny.png"
        )
        let frame = SessionSocket.turnFrame(
            input: "inspect",
            clientMessageId: "msg-1",
            model: "gpt-test",
            effort: "high",
            permissions: "readonly",
            images: [image]
        )
        XCTAssertEqual(frame["type"] as? String, "turn-start")
        XCTAssertEqual(frame["model"] as? String, "gpt-test")
        XCTAssertEqual(frame["effort"] as? String, "high")
        XCTAssertEqual(frame["permissions"] as? String, "readonly")
        let images = try XCTUnwrap(frame["images"] as? [[String: String]])
        XCTAssertEqual(images, [["mime": "image/png", "data": "AAEC"]])
        XCTAssertNotNil(SessionSocket.encodedFrame(frame))
    }

    func testSlashFrameOmitsEmptyArguments() {
        XCTAssertEqual(
            SessionSocket.slashFrame(command: "pwd")["type"] as? String,
            "slash-command"
        )
        XCTAssertNil(SessionSocket.slashFrame(command: "pwd")["args"])
        XCTAssertEqual(
            SessionSocket.slashFrame(command: "cd", args: "/work")["args"] as? String,
            "/work"
        )
    }

    func testQueuedTurnRetriesAndAcknowledgesOnlyTheSendingHead() {
        let queue = [queuedTurn(id: "one", sending: true), queuedTurn(id: "two")]
        let reset = RemotexViewModel.resetSendingHead(queue)
        XCTAssertFalse(reset[0].sending)
        XCTAssertEqual(reset.map(\.id), ["one", "two"])

        let acknowledged = RemotexViewModel.acknowledgeQueuedTurn(
            queue,
            clientMessageId: "msg-one"
        )
        XCTAssertEqual(acknowledged.map(\.id), ["two"])
        XCTAssertEqual(
            RemotexViewModel.acknowledgeQueuedTurn(queue, clientMessageId: "msg-two"),
            queue,
            "an unsent queue item must not be removed by an unrelated echo"
        )
    }

    func testRemoteFolderPathHelpersStayRooted() {
        XCTAssertEqual(RemotexViewModel.parentRemotePath("/work/project/"), "/work")
        XCTAssertEqual(RemotexViewModel.parentRemotePath("/"), "/")
        XCTAssertEqual(RemotexViewModel.joinRemotePath("/work", "project"), "/work/project")
        XCTAssertEqual(RemotexViewModel.joinRemotePath("/", "tmp"), "/tmp")
    }

    func testInventorySocketUsesProviderPathAndAuthenticatedHello() throws {
        let url = try XCTUnwrap(
            InventorySocket.webSocketURL(baseURL: "https://relay.example/team/")
        )
        XCTAssertEqual(url.absoluteString, "wss://relay.example/team/ws/inventory")
        let hello = InventorySocket.helloFrame(
            userToken: "secret",
            clientId: "inventory-fixed"
        )
        XCTAssertEqual(hello["type"] as? String, "hello")
        XCTAssertEqual(hello["token"] as? String, "secret")
        XCTAssertEqual(hello["client_name"] as? String, "iphone")
    }

    func testActiveSessionKeysAreProviderScoped() throws {
        let canonical = try XCTUnwrap(
            RemotexViewModel.activeSessionKey(for: "https://relay.example/team/")
        )
        XCTAssertEqual(
            canonical,
            RemotexViewModel.activeSessionKey(for: "HTTPS://RELAY.EXAMPLE:443/team")
        )
        XCTAssertNotEqual(
            canonical,
            RemotexViewModel.activeSessionKey(for: "https://relay.example/other")
        )
    }

    func testPersistedSessionContainsAttachmentMetadataButNoCredentials() throws {
        let saved = PersistedSession(
            sessionId: "sess_1",
            hostId: "host_1",
            cwd: "/work",
            threadId: "thr_1"
        )
        let data = try JSONEncoder().encode(saved)
        XCTAssertEqual(try JSONDecoder().decode(PersistedSession.self, from: data), saved)
        let text = try XCTUnwrap(String(data: data, encoding: .utf8))
        XCTAssertFalse(text.contains("token"))
        XCTAssertFalse(text.contains("transcript"))
    }

    func testFileBoundaryValidationRejectsOversizeAndHeaderInjection() throws {
        XCTAssertNoThrow(try RelayClient.validateFileSize(RemotexViewModel.maxAttachmentBytes))
        XCTAssertThrowsError(
            try RelayClient.validateFileSize(RemotexViewModel.maxAttachmentBytes + 1)
        )
        XCTAssertEqual(try RelayClient.validatedFileName(" report.txt "), "report.txt")
        for invalid in ["", ".", "..", "a/b", "a\\b", "a\"b", "a\nb"] {
            XCTAssertThrowsError(try RelayClient.validatedFileName(invalid), invalid)
        }
    }

    func testMultipartBodyContainsPathNameAndUnmodifiedBytes() throws {
        let payload = Data([0, 1, 2, 255])
        let body = RelayClient.multipartBody(
            directory: "/work/a folder",
            fileName: "input.bin",
            bytes: payload,
            boundary: "test-boundary"
        )
        let header = String(decoding: body, as: UTF8.self)
        XCTAssertTrue(header.contains("name=\"path\""))
        XCTAssertTrue(header.contains("/work/a folder"))
        XCTAssertTrue(header.contains("filename=\"input.bin\""))
        XCTAssertNotNil(body.range(of: payload))
        XCTAssertTrue(body.suffix(19).elementsEqual(Data("--test-boundary--\r\n".utf8)))
    }

    func testWebSocketCeilingMatchesBase64FileEnvelope() {
        XCTAssertEqual(
            SessionSocket.maximumMessageBytes,
            ((25 * 1024 * 1024 * 4 + 2) / 3) + 4 * 1024 * 1024
        )
        XCTAssertGreaterThan(SessionSocket.maximumMessageBytes, RemotexViewModel.maxAttachmentBytes)
        XCTAssertEqual(InventorySocket.maximumMessageBytes, SessionSocket.maximumMessageBytes)
    }

    func testShareFileNamesCannotEscapeTheTemporaryDirectory() {
        XCTAssertEqual(RemotexViewModel.safeShareFileName("../../report.txt"), "report.txt")
        XCTAssertEqual(RemotexViewModel.safeShareFileName("..\\report.txt"), "report.txt")
        XCTAssertEqual(RemotexViewModel.safeShareFileName(".."), "download.bin")
        XCTAssertEqual(RemotexViewModel.safeShareFileName("bad\nname.txt"), "badname.txt")
    }

    func testCompletionNotificationOnlyFiresForLiveBackgroundTurn() {
        XCTAssertTrue(RemotexViewModel.shouldNotifyTurnCompletion(
            appIsActive: false,
            wasPending: true,
            replayed: false
        ))
        XCTAssertFalse(RemotexViewModel.shouldNotifyTurnCompletion(
            appIsActive: true,
            wasPending: true,
            replayed: false
        ))
        XCTAssertFalse(RemotexViewModel.shouldNotifyTurnCompletion(
            appIsActive: false,
            wasPending: true,
            replayed: true
        ))
        XCTAssertFalse(RemotexViewModel.shouldNotifyTurnCompletion(
            appIsActive: false,
            wasPending: false,
            replayed: false
        ))
    }

    func testThemeCycleIncludesExplicitHighContrast() {
        let setting = ThemeSetting()
        let original = setting.choice
        defer { setting.choice = original }
        setting.choice = .system
        setting.advance()
        XCTAssertEqual(setting.choice, .dark)
        setting.advance()
        XCTAssertEqual(setting.choice, .light)
        setting.advance()
        XCTAssertEqual(setting.choice, .highContrast)
        XCTAssertEqual(setting.choice.colorScheme, .dark)
        setting.advance()
        XCTAssertEqual(setting.choice, .system)
    }
}
