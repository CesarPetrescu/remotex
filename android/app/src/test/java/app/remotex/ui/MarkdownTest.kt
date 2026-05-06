package app.remotex.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTest {
    @Test
    fun balanceMarkdownClosesStreamingCodeFence() {
        assertEquals(
            "```kotlin\nval answer = 42\n```",
            balanceMarkdown("```kotlin\nval answer = 42"),
        )
    }

    @Test
    fun balanceMarkdownClosesInlineCodeAndBoldOnLastLine() {
        assertEquals("done `code`", balanceMarkdown("done `code"))
        assertEquals("**important**", balanceMarkdown("**important"))
    }

    @Test
    fun balanceMarkdownLeavesBalancedTextUnchanged() {
        val text = "plain `code` and **bold**"

        assertEquals(text, balanceMarkdown(text))
    }
}
