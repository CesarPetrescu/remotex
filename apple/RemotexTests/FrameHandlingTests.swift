import XCTest
@testable import Remotex

/// Real relay/daemon frame shapes reduced without opening a network socket.
@MainActor
final class FrameHandlingTests: XCTestCase {
    private func makeViewModel() -> RemotexViewModel {
        RemotexViewModel()
    }

    private func sessionEvent(_ kind: String, _ data: [String: Any]) -> [String: Any] {
        ["type": "session-event", "event": ["kind": kind, "data": data]]
    }

    // MARK: - Item lifecycle retained from ios-xctest

    func testToolCallItemBecomesAToolRow() {
        let vm = makeViewModel()
        vm.handle(frame: sessionEvent("item-started", [
            "item_id": "it_1",
            "item_type": "tool_call",
            "tool": "shell",
            "args": ["command": "ls -la"],
        ]))

        XCTAssertEqual(vm.stream.count, 1)
        XCTAssertEqual(vm.stream[0].role, .tool)
        XCTAssertEqual(vm.stream[0].title, "shell")
        XCTAssertEqual(vm.stream[0].detail, "ls -la")
        XCTAssertFalse(vm.stream[0].completed)
    }

    func testDeltasAppendToTheItemBody() {
        let vm = makeViewModel()
        vm.handle(frame: sessionEvent("item-started", [
            "item_id": "it_1", "item_type": "tool_call", "tool": "shell",
        ]))
        vm.handle(frame: sessionEvent("item-delta", ["item_id": "it_1", "delta": "one\n"]))
        vm.handle(frame: sessionEvent("item-delta", ["item_id": "it_1", "delta": "two\n"]))
        XCTAssertEqual(vm.stream[0].text, "one\ntwo\n")
    }

    func testPatchReplacesInsteadOfAppending() {
        let vm = makeViewModel()
        vm.handle(frame: sessionEvent("item-started", [
            "item_id": "fc_1", "item_type": "tool_call", "tool": "edit",
        ]))
        vm.handle(frame: sessionEvent("item-patch", ["item_id": "fc_1", "output": "first"]))
        vm.handle(frame: sessionEvent("item-patch", ["item_id": "fc_1", "output": "second"]))
        XCTAssertEqual(vm.stream[0].text, "second")
        XCTAssertFalse(vm.stream[0].completed)
    }

    func testCompletedItemUsesOutputAndMarksComplete() {
        let vm = makeViewModel()
        vm.handle(frame: sessionEvent("item-started", [
            "item_id": "it_1", "item_type": "tool_call", "tool": "shell",
        ]))
        vm.handle(frame: sessionEvent("item-completed", [
            "item_id": "it_1", "output": "done\n",
        ]))
        XCTAssertEqual(vm.stream[0].text, "done\n")
        XCTAssertTrue(vm.stream[0].completed)
    }

    func testMCPToolCallRendersArgumentsAndCompletedResult() {
        let vm = makeViewModel()
        vm.handle(frame: sessionEvent("item-started", [
            "item_id": "mcp_1",
            "item_type": "mcp_tool_call",
            "server": "codex-security",
            "tool": "record_scan",
            "arguments": ["scanId": "scan_1"],
            "status": "inProgress",
        ]))

        XCTAssertEqual(vm.stream[0].role, .tool)
        XCTAssertEqual(vm.stream[0].title, "MCP · codex-security.record_scan")
        XCTAssertTrue(vm.stream[0].detail.contains("scan_1"))
        XCTAssertFalse(vm.stream[0].completed)

        vm.handle(frame: sessionEvent("item-completed", [
            "item_id": "mcp_1",
            "item_type": "mcp_tool_call",
            "status": "completed",
            "duration_ms": 37,
            "result": ["content": [["type": "text", "text": "scan saved"]]],
        ]))
        XCTAssertEqual(vm.stream[0].text, "completed\n37ms\nscan saved")
        XCTAssertTrue(vm.stream[0].completed)
    }

    func testDynamicToolCallRendersStructuredResult() {
        let vm = makeViewModel()
        vm.handle(frame: sessionEvent("item-started", [
            "item_id": "dynamic_1",
            "item_type": "dynamic_tool_call",
            "namespace": "security",
            "tool": "rank",
            "arguments": true,
            "status": "inProgress",
        ]))
        vm.handle(frame: sessionEvent("item-completed", [
            "item_id": "dynamic_1",
            "item_type": "dynamic_tool_call",
            "status": "completed",
            "success": true,
            "content_items": [["text": "ranked"]],
        ]))

        XCTAssertEqual(vm.stream[0].title, "TOOL · security.rank")
        XCTAssertEqual(vm.stream[0].detail, "true")
        XCTAssertTrue(vm.stream[0].text.contains("success"))
        XCTAssertTrue(vm.stream[0].text.contains("ranked"))
        XCTAssertTrue(vm.stream[0].completed)
    }

    func testFailedMCPToolCallRetainsFailureStateAndError() {
        let vm = makeViewModel()
        vm.handle(frame: sessionEvent("item-started", [
            "item_id": "mcp_failed",
            "item_type": "mcp_tool_call",
            "server": "codex-security",
            "tool": "record_scan",
            "status": "failed",
            "error": "invalid scan",
        ]))

        XCTAssertTrue(vm.stream[0].completed)
        XCTAssertTrue(vm.stream[0].failed)
        XCTAssertTrue(vm.stream[0].text.contains("error: invalid scan"))
    }

    func testInProgressDynamicToolCallDoesNotTreatNullSuccessAsFailure() {
        let vm = makeViewModel()
        vm.handle(frame: sessionEvent("item-started", [
            "item_id": "dynamic_pending",
            "item_type": "dynamic_tool_call",
            "tool": "rank",
            "status": "inProgress",
            "success": NSNull(),
        ]))

        XCTAssertEqual(vm.stream[0].text, "inProgress")
        XCTAssertFalse(vm.stream[0].failed)
        XCTAssertFalse(vm.stream[0].completed)
    }

    func testCollabAgentToolCallRendersAsTool() {
        let vm = makeViewModel()
        vm.handle(frame: sessionEvent("item-started", [
            "item_id": "collab_1",
            "item_type": "collab_agent_tool_call",
            "tool": "spawnAgent",
            "prompt": "Audit the parser",
            "status": "completed",
            "model": "gpt-test",
            "receiver_thread_ids": ["thread_1", "thread_2"],
        ]))

        XCTAssertEqual(vm.stream[0].role, .tool)
        XCTAssertEqual(vm.stream[0].title, "spawn agent")
        XCTAssertEqual(vm.stream[0].detail, "Audit the parser")
        XCTAssertEqual(vm.stream[0].text, "completed · gpt-test · 2 threads")
        XCTAssertTrue(vm.stream[0].completed)
    }

    func testReplayedItemsArriveCompleted() {
        let vm = makeViewModel()
        vm.handle(frame: sessionEvent("item-started", [
            "item_id": "am_1",
            "item_type": "agent_message",
            "text": "from history",
            "replayed": true,
        ]))
        XCTAssertTrue(vm.stream[0].completed)
    }

    func testUnknownItemFallsBackToSystemRow() {
        let vm = makeViewModel()
        vm.handle(frame: sessionEvent("item-started", [
            "item_id": "cc_1", "item_type": "contextCompaction",
        ]))
        XCTAssertEqual(vm.stream[0].role, .system)
        XCTAssertEqual(vm.stream[0].title, "contextCompaction")
    }

    // MARK: - Attach, resume, and settings

    func testFreshAttachWaitsForSessionStarted() {
        let vm = makeViewModel()
        vm.handle(frame: ["type": "attached", "replay_from": 0, "turn_in_flight": false])
        XCTAssertEqual(vm.status, .connecting)
        XCTAssertFalse(vm.pending)
    }

    func testReplayAttachRestoresTurnState() {
        let vm = makeViewModel()
        vm.handle(frame: ["type": "attached", "replay_from": 41, "turn_in_flight": true])
        XCTAssertEqual(vm.status, .connected)
        XCTAssertTrue(vm.pending)
    }

    func testSessionStartedAppliesResolvedSettings() {
        let vm = makeViewModel()
        vm.handle(frame: sessionEvent("session-started", [
            "transport": "stdio",
            "model": "hint",
            "settings": [
                "model": "gpt-test",
                "effort": "high",
                "permissions": "full",
            ],
        ]))
        XCTAssertEqual(vm.status, .connected)
        XCTAssertEqual(vm.model, "gpt-test")
        XCTAssertEqual(vm.effort, "high")
        XCTAssertEqual(vm.permissions, "full")
    }

    func testHistoryTransportStaysReadOnly() {
        let vm = makeViewModel()
        vm.handle(frame: sessionEvent("session-started", ["transport": "history"]))
        XCTAssertTrue(vm.historyOnly)
        XCTAssertEqual(vm.status, .error)
        XCTAssertEqual(vm.errorMessage, "Saved chat is history-only. Start a new session to continue.")
    }

    func testResumeLifecycleAndSharedTurnReconciliation() {
        let vm = makeViewModel()
        vm.handle(frame: sessionEvent("thread-status", [
            "status": "resuming", "thread_id": "thr_1",
        ]))
        XCTAssertTrue(vm.resuming)
        XCTAssertEqual(vm.status, .connecting)

        vm.handle(frame: sessionEvent("thread-status", [
            "status": "resumed",
            "shared_turn_in_flight": true,
            "settings": ["effort": "xhigh", "permissions": "readonly"],
        ]))
        XCTAssertFalse(vm.resuming)
        XCTAssertEqual(vm.status, .connected)
        XCTAssertTrue(vm.pending)
        XCTAssertEqual(vm.effort, "xhigh")
        XCTAssertEqual(vm.permissions, "readonly")
    }

    func testResumeFailureStopsComposer() {
        let vm = makeViewModel()
        vm.handle(frame: sessionEvent("thread-status", [
            "status": "resume-failed", "error": "rollout missing",
        ]))
        XCTAssertFalse(vm.resuming)
        XCTAssertFalse(vm.pending)
        XCTAssertEqual(vm.status, .error)
        XCTAssertEqual(vm.errorMessage, "rollout missing")
    }

    func testLiveSessionSettingsReplaceDisplayedValues() {
        let vm = makeViewModel()
        vm.handle(frame: sessionEvent("session-settings", [
            "model": "new-model", "effort": "medium", "permissions": "default",
        ]))
        XCTAssertEqual(vm.model, "new-model")
        XCTAssertEqual(vm.effort, "medium")
        XCTAssertEqual(vm.permissions, "default")
    }

    // MARK: - Failures and prompts

    func testNonfatalErrorDoesNotWedgeSession() {
        let vm = makeViewModel()
        vm.handle(frame: ["type": "attached", "replay_from": 5])
        vm.handle(frame: ["type": "error", "error": "host offline"])
        XCTAssertEqual(vm.status, .connected)
        XCTAssertEqual(vm.errorMessage, "host offline")
    }

    func testFatalErrorStopsTheSession() {
        let vm = makeViewModel()
        vm.handle(frame: ["type": "attached", "replay_from": 5, "turn_in_flight": true])
        vm.handle(frame: ["type": "error", "error": "session closed", "fatal": true])
        XCTAssertEqual(vm.status, .disconnected)
        XCTAssertFalse(vm.pending)
        XCTAssertEqual(vm.connectionMessage, "session closed")
    }

    func testTurnAndSteerErrorsAreVisible() {
        let vm = makeViewModel()
        vm.handle(frame: sessionEvent("steer-failed", ["error": "turn changed"]))
        XCTAssertEqual(vm.errorMessage, "turn changed")
        vm.handle(frame: sessionEvent("turn-completed", ["error": "codex exited"]))
        XCTAssertEqual(vm.errorMessage, "codex exited")
        XCTAssertFalse(vm.pending)
    }

    func testPermissionApprovalRetainsExactPayloadAndDecisions() throws {
        let vm = makeViewModel()
        vm.handle(frame: sessionEvent("approval-request", [
            "approval_id": "appr_1",
            "kind": "permissions",
            "permissions": ["network": true, "roots": ["/work"]],
            "decisions": ["cancel", "accept"],
        ]))
        let prompt = try XCTUnwrap(vm.pendingApprovals.first)
        XCTAssertTrue(prompt.permissions?.contains("\"network\" : true") == true)
        XCTAssertTrue(prompt.permissions?.contains("/work") == true)
        XCTAssertEqual(prompt.decisions, ["cancel", "accept"])
    }

    func testApprovalDecisionFallbackOnlyWhenFieldIsAbsent() {
        let vm = makeViewModel()
        vm.handle(frame: sessionEvent("approval-request", ["approval_id": "fallback"]))
        vm.handle(frame: sessionEvent("approval-request", [
            "approval_id": "empty", "decisions": [String](),
        ]))
        XCTAssertEqual(
            vm.pendingApprovals[0].decisions,
            ["accept", "acceptForSession", "decline", "cancel"]
        )
        XCTAssertEqual(vm.pendingApprovals[1].decisions, [])
    }

    func testSecretUserInputFlagIsRetained() {
        let vm = makeViewModel()
        vm.handle(frame: sessionEvent("user-input-request", [
            "call_id": "ui_1",
            "questions": [[
                "id": "password",
                "header": "Credential",
                "question": "Token?",
                "isSecret": true,
            ]],
        ]))
        XCTAssertTrue(vm.pendingUserInputs[0].questions[0].isSecret)
    }

    func testMalformedFramesAreIgnored() {
        let vm = makeViewModel()
        vm.handle(frame: [:])
        vm.handle(frame: ["type": "session-event"])
        vm.handle(frame: ["type": "session-event", "event": ["kind": 42]])
        vm.handle(frame: sessionEvent("item-delta", [:]))
        vm.handle(frame: sessionEvent("unknown", ["x": 1]))
        XCTAssertTrue(vm.stream.isEmpty)
        XCTAssertTrue(vm.pendingApprovals.isEmpty)
    }

    func testGoalCollaborationAndTokenStateAreReduced() throws {
        let vm = makeViewModel()
        vm.handle(frame: sessionEvent("goal-snapshot", [
            "goal": [
                "thread_id": "thr_1",
                "objective": "Ship it",
                "status": "budget_limited",
                "token_budget": "5000",
                "tokens_used": 1250,
            ],
        ]))
        let goal = try XCTUnwrap(vm.goal)
        XCTAssertEqual(goal.status, "budgetLimited")
        XCTAssertEqual(goal.tokenBudget, 5_000)
        XCTAssertEqual(goal.tokensUsed, 1_250)

        vm.handle(frame: sessionEvent("collab-modes", [
            "modes": [["name": "default"], ["name": "plan"]],
        ]))
        XCTAssertEqual(vm.collaborationModes, ["default", "plan"])

        vm.handle(frame: sessionEvent("token-usage", [
            "input": "100", "output": 20, "cached_input": 50, "reasoning_output": 4,
        ]))
        XCTAssertEqual(vm.tokensInput, 100)
        XCTAssertEqual(vm.tokensOutput, 20)
        XCTAssertEqual(vm.tokensCached, 50)
        XCTAssertEqual(vm.tokensReasoning, 4)

        vm.handle(frame: sessionEvent("goal-cleared", [:]))
        XCTAssertNil(vm.goal)
    }

    func testSlashAcknowledgementTracksPlanMode() {
        let vm = makeViewModel()
        vm.handle(frame: sessionEvent("slash-ack", [
            "command": "plan", "ok": true, "message": "next turn will use plan mode",
        ]))
        XCTAssertTrue(vm.planMode)
        vm.handle(frame: sessionEvent("slash-ack", [
            "command": "default", "ok": true,
        ]))
        XCTAssertFalse(vm.planMode)
    }

    func testUserMessageRetainsImageCount() {
        let vm = makeViewModel()
        vm.handle(frame: sessionEvent("item-started", [
            "item_id": "msg-images",
            "item_type": "user_message",
            "text": "look",
            "image_count": 2,
        ]))
        XCTAssertEqual(vm.stream.first?.imageCount, 2)
    }
}
