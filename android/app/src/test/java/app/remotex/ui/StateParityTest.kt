package app.remotex.ui

import app.remotex.net.RelayHttpException
import androidx.compose.ui.unit.dp
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StateParityTest {
    private val json = Json

    @Test
    fun defaultStateStartsSignedOut() {
        assertEquals("", UiState().userToken)
    }

    @Test
    fun hostConnectionErrorsUseActionableCopy() {
        assertEquals(
            "That access token was not accepted.",
            hostConnectionErrorMessage(RelayHttpException(401)),
        )
        assertEquals(
            "Too many attempts. Try again in 30 seconds.",
            hostConnectionErrorMessage(RelayHttpException(429, "30")),
        )
        assertEquals(
            "Could not reach this relay. Check its address and your connection.",
            hostConnectionErrorMessage(IOException("connection refused")),
        )
    }

    @Test
    fun adaptiveLayoutUsesWidthAndCompactHeightBoundaries() {
        assertFalse(useTwoPane(599.dp, 800.dp))
        assertTrue(useTwoPane(600.dp, 480.dp))
        assertFalse(useTwoPane(800.dp, 479.dp))
        assertFalse(usePermanentTelemetryPane(1199.dp, 800.dp))
        assertTrue(usePermanentTelemetryPane(1200.dp, 480.dp))
    }

    @Test
    fun sessionDetailsApplyThreadIdentityAndResolvedPickerSettings() {
        val state = UiState(
            session = SessionInfo("session-1", "host-1"),
            model = "old-model",
            effort = "medium",
            permissions = PermissionsMode.Default,
        )
        val data = json.parseToJsonElement(
            """{
                "thread_id":"thread-9",
                "model":"session-model",
                "cwd":"/work",
                "settings":{
                    "model":"resolved-model",
                    "effort":"high",
                    "permissions":"full"
                }
            }""",
        ).jsonObject

        val next = applySessionDetails(state, data)

        assertEquals("thread-9", next.session?.threadId)
        assertEquals("/work", next.session?.cwd)
        assertEquals("session-model", next.session?.model)
        assertEquals("resolved-model", next.model)
        assertEquals("high", next.effort)
        assertEquals(PermissionsMode.Full, next.permissions)
    }

    @Test
    fun partialResolvedSettingsPreserveUnknownValues() {
        val state = UiState(
            model = "model-a",
            effort = "xhigh",
            permissions = PermissionsMode.ReadOnly,
        )
        val settings = json.parseToJsonElement(
            """{"effort":"low","permissions":"custom-policy"}""",
        ).jsonObject

        val next = applyResolvedSettings(state, settings)

        assertEquals("model-a", next.model)
        assertEquals("low", next.effort)
        assertEquals(PermissionsMode.ReadOnly, next.permissions)
    }

    @Test
    fun sharedIdleSnapshotClearsPendingPrompts() {
        val state = UiState(
            pending = true,
            pendingApprovals = listOf(approval("a")),
            pendingUserInputs = listOf(UserInputPrompt("u", questions = emptyList())),
        )

        val next = reconcileSharedTurn(state, false)

        assertFalse(next.pending)
        assertTrue(next.pendingApprovals.isEmpty())
        assertTrue(next.pendingUserInputs.isEmpty())
        assertTrue(reconcileSharedTurn(state, true).pending)
    }

    @Test
    fun turnAndFatalErrorsAreVisibleAndTerminal() {
        val queued = queued("one")
        val state = UiState(
            status = Status.Connected,
            pending = true,
            queuedTurns = listOf(queued),
        )
        val completion = json.parseToJsonElement("""{"error":"host went offline"}""").jsonObject

        val completed = applyTurnCompletion(state, completion)
        val fatal = applyFatalSessionError(state, "session is closed")

        assertFalse(completed.pending)
        assertEquals("host went offline", completed.error)
        assertEquals(Status.Disconnected, fatal.status)
        assertFalse(fatal.pending)
        assertEquals("session is closed", fatal.error)
        assertTrue(fatal.queuedTurns.isEmpty())
    }

    @Test
    fun queuedTurnsRemainFifoAcrossSendRetryAndAcknowledgement() {
        val first = queued("first")
        val second = queued("second")

        val sending = markQueuedTurnSending(listOf(first, second), first.id)
        assertTrue(sending.first().sending)
        assertFalse(sending.last().sending)

        val retryable = resetSendingQueuedTurn(sending)
        assertFalse(retryable.first().sending)

        val acknowledged = acknowledgeQueuedTurn(
            retryable,
            first.clientMessageId,
        )
        assertEquals(listOf(second.id), acknowledged.map { it.id })
    }

    @Test
    fun queuedTurnFrameCapturesSettingsAndImages() {
        val image = PendingImage("content://one", "image/png", "YWJj", "one.png", 3)
        val turn = queued("follow up").copy(
            model = "gpt-test",
            effort = "high",
            permissions = PermissionsMode.ReadOnly,
            images = listOf(image),
        )

        val frame = json.parseToJsonElement(buildTurnStartFrame(turn)).jsonObject

        assertEquals("turn-start", frame["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals(turn.clientMessageId, frame["client_message_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("gpt-test", frame["model"]?.jsonPrimitive?.contentOrNull)
        assertEquals("high", frame["effort"]?.jsonPrimitive?.contentOrNull)
        assertEquals("readonly", frame["permissions"]?.jsonPrimitive?.contentOrNull)
        val images = frame["images"] as JsonArray
        assertEquals("YWJj", images.first().jsonObject["data"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun previewIdsAreUniqueAndIncludeThread() {
        assertEquals("preview_thread-1_0", previewEventId("thread-1", 0))
        assertEquals("preview_thread-1_1", previewEventId("thread-1", 1))
    }

    private fun queued(label: String) = QueuedTurn(
        id = "queued-$label",
        clientMessageId = "msg-$label",
        text = label,
        model = "",
        effort = "",
        permissions = PermissionsMode.Default,
    )

    private fun approval(id: String) = ApprovalPrompt(
        approvalId = id,
        kind = "command",
        reason = null,
        command = null,
        cwd = null,
        decisions = listOf("accept"),
    )
}
