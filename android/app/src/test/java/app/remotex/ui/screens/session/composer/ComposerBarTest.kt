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
            onSlashCommand = { command, args -> commands += command to args },
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
            onSlashCommand = { _, _ -> },
            onSend = sent::add,
        )

        assertTrue(handled)
        assertEquals(listOf(""), sent)
    }

    @Test
    fun blankSubmissionWithoutAttachmentsIsIgnored() {
        val sent = mutableListOf<String>()

        val handled = handleSubmit(
            text = "   ",
            hasAttachments = false,
            onSlashCommand = { _, _ -> },
            onSend = sent::add,
        )

        assertFalse(handled)
        assertTrue(sent.isEmpty())
    }
}
