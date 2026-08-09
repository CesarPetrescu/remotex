package app.remotex.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Contract (F): pending approvals and pending user-input requests are
 * ORDERED QUEUES. A second concurrent prompt must never overwrite or hide an
 * earlier unanswered one. Mirrors apps/web/src/hooks/useRemotex.test.js.
 */
class PromptQueueTest {

    @Test
    fun attachedTurnSnapshotAndReplayCursorPreserveReconnectState() {
        val active = Json.parseToJsonElement(
            """{"type":"attached","turn_in_flight":true}""",
        ).jsonObject
        val idle = Json.parseToJsonElement(
            """{"type":"attached","turn_in_flight":false}""",
        ).jsonObject
        val oldRelay = Json.parseToJsonElement("""{"type":"attached"}""").jsonObject

        assertEquals(true, attachedTurnInFlight(active))
        assertEquals(false, attachedTurnInFlight(idle))
        assertNull(attachedTurnInFlight(oldRelay))
        assertEquals(Status.Connected, attachedStatus(Json.parseToJsonElement(
            """{"type":"attached","replay_from":42}""",
        ).jsonObject))
        assertEquals(Status.Connecting, attachedStatus(oldRelay))
        val cursors = mutableMapOf("sess_1" to 42L)
        assertEquals(42L, prepareSessionReplayCursor(cursors, "sess_1", replayFromStart = false))
        assertEquals(0L, prepareSessionReplayCursor(cursors, "sess_1", replayFromStart = true))
        assertEquals(0L, cursors["sess_1"])
    }

    private fun approval(id: String, command: String = "ls") = ApprovalPrompt(
        approvalId = id,
        kind = "command",
        reason = null,
        command = command,
        cwd = null,
        decisions = listOf("accept", "decline"),
    )

    private fun userInput(id: String) = UserInputPrompt(callId = id, questions = emptyList())

    @Test
    fun secondApprovalQueuesBehindTheFirst() {
        var queue = enqueuePrompt(emptyList<ApprovalPrompt>(), approval("a1"), approvalKey)
        queue = enqueuePrompt(queue, approval("a2"), approvalKey)

        assertEquals(listOf("a1", "a2"), queue.map { it.approvalId })
        // The head is what the dialog renders; the second is still there.
        assertEquals("a1", UiState(pendingApprovals = queue).pendingApproval?.approvalId)
    }

    @Test
    fun repeatedApprovalRefreshesInPlaceInsteadOfDoubleInserting() {
        val queue = listOf(approval("a1"), approval("a2"))

        val next = enqueuePrompt(queue, approval("a1", command = "rm -rf /"), approvalKey)

        assertEquals(listOf("a1", "a2"), next.map { it.approvalId })
        assertEquals("rm -rf /", next.first().command)
    }

    @Test
    fun answeringTheHeadRevealsTheNext() {
        val queue = listOf(approval("a1"), approval("a2"))

        val next = dequeuePrompt(queue, "a1", approvalKey)

        assertEquals(listOf("a2"), next.map { it.approvalId })
        assertEquals("a2", UiState(pendingApprovals = next).pendingApproval?.approvalId)
    }

    @Test
    fun resolutionWithoutAnIdDropsTheHeadOnly() {
        val queue = listOf(approval("a1"), approval("a2"))

        assertEquals(listOf("a2"), dequeuePrompt(queue, null, approvalKey).map { it.approvalId })
        assertEquals(listOf("a2"), dequeuePrompt(queue, "", approvalKey).map { it.approvalId })
    }

    @Test
    fun peerAnsweredPromptLeavesTheQueueWhenTheRelayResolvesIt() {
        val queue = listOf(approval("a1"), approval("a2"), approval("a3"))

        // A peer client answered the middle one — the head must not move.
        val next = dequeuePrompt(queue, "a2", approvalKey)

        assertEquals(listOf("a1", "a3"), next.map { it.approvalId })
    }

    @Test
    fun snapshotDecidesMembershipButLocalQueueKeepsItsOrder() {
        val local = listOf(approval("a1"), approval("a2"), approval("a3"))
        // Relay says a1 is gone (a peer answered it), a3/a2 remain, and a4 is
        // new to us. Arrival order of the ones we already knew is preserved.
        val incoming = listOf(approval("a3", command = "fresh"), approval("a2"), approval("a4"))

        val merged = reconcileQueue(local, incoming, approvalKey)

        assertEquals(listOf("a2", "a3", "a4"), merged.map { it.approvalId })
        // The snapshot's payload wins for entries it carries.
        assertEquals("fresh", merged.first { it.approvalId == "a3" }.command)
    }

    @Test
    fun snapshotOrderWinsWhenTheRelaySuppliesIt() {
        // The relay handed back a claim it could not forward: a1 is older
        // than a2 and has to land in front of it again, not at the tail
        // (contract F). Mirrors useRemotex.test.js.
        val local = listOf(approval("a2").copy(order = 2))
        val incoming = listOf(approval("a2").copy(order = 2), approval("a1").copy(order = 1))

        val merged = reconcileQueue(local, incoming, approvalKey, approvalOrder)

        assertEquals(listOf("a1", "a2"), merged.map { it.approvalId })
    }

    @Test
    fun emptySnapshotClearsTheQueue() {
        val local = listOf(approval("a1"), approval("a2"))

        assertEquals(
            emptyList<ApprovalPrompt>(),
            reconcileQueue(local, emptyList<ApprovalPrompt>(), approvalKey),
        )
    }

    @Test
    fun userInputPromptsQueueIndependentlyOfApprovals() {
        var inputs = enqueuePrompt(emptyList<UserInputPrompt>(), userInput("ui1"), userInputKey)
        inputs = enqueuePrompt(inputs, userInput("ui2"), userInputKey)
        val state = UiState(
            pendingApprovals = listOf(approval("a1")),
            pendingUserInputs = inputs,
        )

        assertEquals("a1", state.pendingApproval?.approvalId)
        assertEquals("ui1", state.pendingUserInput?.callId)

        val afterAnswer = state.copy(
            pendingUserInputs = dequeuePrompt(state.pendingUserInputs, "ui1", userInputKey),
        )
        assertEquals("ui2", afterAnswer.pendingUserInput?.callId)
        assertEquals("a1", afterAnswer.pendingApproval?.approvalId)
    }

    @Test
    fun noPendingPromptsMeansNoHeads() {
        val state = UiState()

        assertNull(state.pendingApproval)
        assertNull(state.pendingUserInput)
    }

    @Test
    fun pendingPromptsFrameParsesIntoBothQueuesInArrivalOrder() {
        val frame = Json.parseToJsonElement(
            """
            {
              "type": "pending-prompts",
              "session_id": "sess_1",
              "approvals": [
                {"approval_id": "a1", "kind": "command", "command": "ls",
                 "decisions": ["accept", "decline"]},
                {"approval_id": "a2", "kind": "file_change"},
                {"kind": "command"}
              ],
              "user_inputs": [
                {"call_id": "ui1", "turn_id": "turn_1",
                 "questions": [
                   {"id": "q1", "header": "pick one", "question": "which?",
                    "options": [{"label": "yes", "description": "do it"}]}
                 ]}
              ]
            }
            """.trimIndent(),
        ).jsonObject

        val snapshot = normalizePromptSnapshot(frame)

        // The id-less entry is dropped, everything else keeps arrival order.
        assertEquals(listOf("a1", "a2"), snapshot.approvals.map { it.approvalId })
        assertEquals(listOf("accept", "decline"), snapshot.approvals.first().decisions)
        // Missing decisions fall back to the full set.
        assertEquals(
            listOf("accept", "acceptForSession", "decline", "cancel"),
            snapshot.approvals[1].decisions,
        )
        val prompt = snapshot.userInputs.single()
        assertEquals("ui1", prompt.callId)
        assertEquals("turn_1", prompt.turnId)
        assertEquals("q1", prompt.questions.single().id)
        assertEquals("yes", prompt.questions.single().options.single().label)
        assertEquals("do it", prompt.questions.single().options.single().description)
    }

    @Test
    fun snapshotDoesNotResurrectAPromptTheRelayNoLongerLists() {
        val frame = Json.parseToJsonElement(
            """{"type":"pending-prompts","approvals":[{"approval_id":"a2"}],"user_inputs":[]}""",
        ).jsonObject
        val local = listOf(approval("a1"), approval("a2"))

        val snapshot = normalizePromptSnapshot(frame)
        val merged = reconcileQueue(local, snapshot.approvals, approvalKey)

        assertEquals(listOf("a2"), merged.map { it.approvalId })
    }
}
