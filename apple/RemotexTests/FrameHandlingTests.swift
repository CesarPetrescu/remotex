import XCTest
@testable import Remotex

/// Frames here are the real shapes the daemon emits (see
/// `services/daemon/adapters/stdio.py`), so these tests fail if the wire
/// contract drifts on either side.
@MainActor
final class FrameHandlingTests: XCTestCase {

    private func makeViewModel() -> RemotexViewModel {
        RemotexViewModel()
    }

    /// Wrap event data the way the relay does: `{type, event: {kind, data}}`.
    private func sessionEvent(_ kind: String, _ data: [String: Any]) -> [String: Any] {
        ["type": "session-event", "event": ["kind": kind, "data": data]]
    }

    // MARK: - item lifecycle

    func testToolCallItemBecomesAToolRow() {
        let vm = makeViewModel()

        vm.handle(frame: sessionEvent("item-started", [
            "item_id": "it_1",
            "item_type": "tool_call",
            "tool": "shell",
            "args": ["command": "ls -la"],
        ]))

        XCTAssertEqual(vm.stream.count, 1)
        let item = vm.stream[0]
        XCTAssertEqual(item.id, "it_1")
        XCTAssertEqual(item.role, .tool)
        XCTAssertEqual(item.title, "shell")
        XCTAssertEqual(item.detail, "ls -la")
        XCTAssertFalse(item.completed)
    }

    func testDeltasAppendToTheItemBody() {
        let vm = makeViewModel()
        vm.handle(frame: sessionEvent("item-started", [
            "item_id": "it_1", "item_type": "tool_call", "tool": "shell",
        ]))

        vm.handle(frame: sessionEvent("item-delta", ["item_id": "it_1", "delta": "line1\n"]))
        vm.handle(frame: sessionEvent("item-delta", ["item_id": "it_1", "delta": "line2\n"]))

        XCTAssertEqual(vm.stream[0].text, "line1\nline2\n")
    }

    /// `item-patch` must REPLACE, not append: codex resends the whole patch on
    /// every `item/fileChange/patchUpdated`, so appending duplicates the diff.
    func testPatchReplacesTheBodyInsteadOfAppending() {
        let vm = makeViewModel()
        vm.handle(frame: sessionEvent("item-started", [
            "item_id": "fc_1", "item_type": "tool_call", "tool": "edit",
        ]))

        vm.handle(frame: sessionEvent("item-patch", [
            "item_id": "fc_1",
            "item_type": "tool_call",
            "output": "@@ -1 +1 @@\n-a\n+b\n",
        ]))
        vm.handle(frame: sessionEvent("item-patch", [
            "item_id": "fc_1",
            "item_type": "tool_call",
            "output": "@@ -1,2 +1,2 @@\n-a\n+b\n+c\n",
        ]))

        XCTAssertEqual(vm.stream.count, 1)
        XCTAssertEqual(vm.stream[0].text, "@@ -1,2 +1,2 @@\n-a\n+b\n+c\n")
        XCTAssertFalse(vm.stream[0].completed, "a patch is progress, not completion")
    }

    func testPatchForAnUnknownItemIsIgnored() {
        let vm = makeViewModel()
        vm.handle(frame: sessionEvent("item-patch", ["item_id": "nope", "output": "x"]))
        XCTAssertTrue(vm.stream.isEmpty)
    }

    func testCompletedItemPrefersOutputOverText() {
        let vm = makeViewModel()
        vm.handle(frame: sessionEvent("item-started", [
            "item_id": "it_1", "item_type": "tool_call", "tool": "shell",
        ]))

        vm.handle(frame: sessionEvent("item-completed", [
            "item_id": "it_1",
            "item_type": "tool_call",
            "output": "total 0\n",
        ]))

        XCTAssertEqual(vm.stream[0].text, "total 0\n")
        XCTAssertTrue(vm.stream[0].completed)
    }

    func testAgentMessageStreamsThenCompletes() {
        let vm = makeViewModel()
        vm.handle(frame: sessionEvent("item-started", [
            "item_id": "am_1", "item_type": "agent_message",
        ]))
        vm.handle(frame: sessionEvent("item-delta", ["item_id": "am_1", "delta": "Hel"]))
        vm.handle(frame: sessionEvent("item-delta", ["item_id": "am_1", "delta": "lo"]))
        vm.handle(frame: sessionEvent("item-completed", [
            "item_id": "am_1", "item_type": "agent_message", "text": "Hello",
        ]))

        XCTAssertEqual(vm.stream[0].role, .agent)
        XCTAssertEqual(vm.stream[0].text, "Hello")
        XCTAssertTrue(vm.stream[0].completed)
    }

    func testReplayedItemsArriveAlreadyCompleted() {
        let vm = makeViewModel()
        vm.handle(frame: sessionEvent("item-started", [
            "item_id": "am_1",
            "item_type": "agent_message",
            "text": "from history",
            "replayed": true,
        ]))
        XCTAssertTrue(vm.stream[0].completed)
    }

    /// The daemon maps codex's `fileChange` onto `tool_call` and flattens the
    /// diff into `output` (Issues.md I-006), so an edit must land as a tool row
    /// carrying the diff — not an empty system row.
    func testFileChangeArrivesAsAToolRowWithItsDiff() {
        let vm = makeViewModel()

        vm.handle(frame: sessionEvent("item-completed", [
            "item_id": "fc_1",
            "item_type": "tool_call",
            "tool": "edit",
            "args": ["command": "update /w/notes.txt"],
            "output": "@@ -1 +1 @@\n-hello\n+hello world\n",
        ]))
        // No item-started for this id, so nothing to update — the guard holds.
        XCTAssertTrue(vm.stream.isEmpty)

        vm.handle(frame: sessionEvent("item-started", [
            "item_id": "fc_2",
            "item_type": "tool_call",
            "tool": "edit",
            "args": ["command": "add /w/notes.txt"],
            "output": "hello\n",
        ]))

        XCTAssertEqual(vm.stream.count, 1)
        XCTAssertEqual(vm.stream[0].role, .tool)
        XCTAssertEqual(vm.stream[0].title, "edit")
        XCTAssertEqual(vm.stream[0].detail, "add /w/notes.txt")
        XCTAssertEqual(vm.stream[0].text, "hello\n")
    }

    func testUnknownItemTypeFallsBackToASystemRow() {
        let vm = makeViewModel()
        vm.handle(frame: sessionEvent("item-started", [
            "item_id": "cc_1", "item_type": "contextCompaction",
        ]))

        XCTAssertEqual(vm.stream[0].role, .system)
        // NOTE: web and Android humanize this to "context compaction"; iOS
        // still shows the raw type. Tracked in Issues.md I-012.
        XCTAssertEqual(vm.stream[0].title, "contextCompaction")
    }

    // MARK: - session + status frames

    func testAttachedFrameMarksConnected() {
        let vm = makeViewModel()
        vm.handle(frame: ["type": "attached"])
        XCTAssertEqual(vm.status, .connected)
    }

    func testErrorFrameSurfacesTheMessage() {
        let vm = makeViewModel()
        vm.handle(frame: ["type": "error", "error": "host offline"])
        XCTAssertEqual(vm.status, .error)
        XCTAssertEqual(vm.errorMessage, "host offline")
    }

    func testErrorFrameWithoutAMessageStillReportsSomething() {
        let vm = makeViewModel()
        vm.handle(frame: ["type": "error"])
        XCTAssertEqual(vm.errorMessage, "Relay error")
    }

    func testSessionClosedDisconnects() {
        let vm = makeViewModel()
        vm.handle(frame: ["type": "attached"])
        vm.handle(frame: ["type": "session-closed"])
        XCTAssertEqual(vm.status, .disconnected)
        XCTAssertFalse(vm.pending)
    }

    /// `thread/compacted` reaches clients as a slash-ack (Issues.md I-009).
    func testSlashAckRendersCommandAndMessage() {
        let vm = makeViewModel()
        vm.handle(frame: sessionEvent("slash-ack", [
            "command": "compact", "ok": true, "message": "context compacted",
        ]))

        XCTAssertEqual(vm.stream.count, 1)
        XCTAssertEqual(vm.stream[0].role, .system)
        XCTAssertEqual(vm.stream[0].title, "/compact")
        XCTAssertEqual(vm.stream[0].text, "context compacted")
    }

    func testSlashAckFallsBackToTheErrorText() {
        let vm = makeViewModel()
        vm.handle(frame: sessionEvent("slash-ack", [
            "command": "cd", "ok": false, "error": "no such directory",
        ]))
        XCTAssertEqual(vm.stream[0].text, "no such directory")
    }

    // MARK: - pending prompts

    func testApprovalRequestBecomesAPendingPrompt() {
        let vm = makeViewModel()
        vm.handle(frame: sessionEvent("approval-request", [
            "approval_id": "appr_1",
            "kind": "command",
            "command": "rm -rf build",
            "cwd": "/w",
            "decisions": ["accept", "decline"],
        ]))

        XCTAssertEqual(vm.pendingApprovals.count, 1)
        let prompt = vm.pendingApprovals[0]
        XCTAssertEqual(prompt.approvalId, "appr_1")
        XCTAssertEqual(prompt.kind, "command")
        XCTAssertEqual(prompt.command, "rm -rf build")
        XCTAssertEqual(prompt.decisions, ["accept", "decline"])
        XCTAssertTrue(vm.hasPendingPrompts)
    }

    /// A replayed frame must update in place, not double-insert, and must not
    /// displace an earlier unanswered prompt.
    func testReplayedApprovalUpdatesInPlaceAndKeepsQueueOrder() {
        let vm = makeViewModel()
        vm.handle(frame: sessionEvent("approval-request", [
            "approval_id": "appr_1", "kind": "command", "command": "first",
        ]))
        vm.handle(frame: sessionEvent("approval-request", [
            "approval_id": "appr_2", "kind": "command", "command": "second",
        ]))
        vm.handle(frame: sessionEvent("approval-request", [
            "approval_id": "appr_1", "kind": "command", "command": "first (again)",
        ]))

        XCTAssertEqual(vm.pendingApprovals.map(\.approvalId), ["appr_1", "appr_2"])
        XCTAssertEqual(vm.pendingApprovals[0].command, "first (again)")
    }

    func testApprovalWithoutAnIdIsIgnored() {
        let vm = makeViewModel()
        vm.handle(frame: sessionEvent("approval-request", ["kind": "command"]))
        XCTAssertTrue(vm.pendingApprovals.isEmpty)
        XCTAssertFalse(vm.hasPendingPrompts)
    }

    // MARK: - malformed input

    func testMalformedFramesAreIgnoredRatherThanCrashing() {
        let vm = makeViewModel()

        vm.handle(frame: [:])
        vm.handle(frame: ["type": "session-event"])                     // no event
        vm.handle(frame: ["type": "session-event", "event": ["kind": 42]])  // kind not a string
        vm.handle(frame: sessionEvent("item-delta", [:]))                // no item_id
        vm.handle(frame: sessionEvent("totally-unknown-kind", ["x": 1]))
        vm.handle(frame: ["type": "wat"])

        XCTAssertTrue(vm.stream.isEmpty)
        XCTAssertTrue(vm.pendingApprovals.isEmpty)
        XCTAssertNil(vm.errorMessage)
    }
}
