package app.remotex.ui.screens.session.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerBarTest {

    @Test
    fun collaborationModesAreDiscoverableAndRoutedAsAControlCommand() {
        val commands = mutableListOf<Pair<String, String>>()

        assertTrue(KNOWN_SLASHES.any { it.id == "collab" })
        val handled = handleSubmit(
            text = "/collab",
            hasAttachments = false,
            onSlashCommand = { command, args ->
                commands += command to args
                true
            },
            onSend = { error("slash command became a user turn") },
        )

        assertTrue(handled)
        assertEquals(listOf("collab" to ""), commands)
    }

    @Test
    fun imageOnlySubmissionReachesTheViewModel() {
        val sent = mutableListOf<String>()

        val handled = handleSubmit(
            text = "",
            hasAttachments = true,
            onSlashCommand = { _, _ -> true },
            onSend = { sent += it; true },
        )

        assertTrue(handled)
        assertEquals(listOf(""), sent)
    }

    @Test
    fun slashTextWithAnImageIsSentAsAPrompt() {
        val commands = mutableListOf<Pair<String, String>>()
        val sent = mutableListOf<String>()

        val handled = handleSubmit(
            text = "/goal inspect this screenshot",
            hasAttachments = true,
            onSlashCommand = { command, args ->
                commands += command to args
                true
            },
            onSend = { sent += it; true },
        )

        assertTrue(handled)
        assertTrue(commands.isEmpty())
        assertEquals(listOf("/goal inspect this screenshot"), sent)
    }

    @Test
    fun blankSubmissionWithoutAttachmentsIsIgnored() {
        val sent = mutableListOf<String>()

        val handled = handleSubmit(
            text = "   ",
            hasAttachments = false,
            onSlashCommand = { _, _ -> true },
            onSend = { sent += it; true },
        )

        assertFalse(handled)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun rejectedSubmissionKeepsTheDraft() {
        val handled = handleSubmit(
            text = "keep this text",
            hasAttachments = true,
            onSlashCommand = { _, _ -> true },
            onSend = { false },
        )

        assertFalse(handled)
    }

    @Test
    fun rejectedSlashCommandKeepsTheDraft() {
        val handled = handleSubmit(
            text = "/cd /work",
            hasAttachments = false,
            onSlashCommand = { _, _ -> false },
            onSend = { error("known slash became a user turn") },
        )

        assertFalse(handled)
    }
}
