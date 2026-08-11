package app.remotex.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.remotex.security.SecureTokenStore
import app.remotex.persistence.ActiveSession
import app.remotex.persistence.ActiveSessionStore
import app.remotex.ui.components.TokenField
import app.remotex.ui.app.RemotexBar
import app.remotex.ui.screens.session.ApprovalDialog
import app.remotex.ui.screens.session.UserInputDialog
import app.remotex.ui.screens.session.composer.ComposerBar
import app.remotex.ui.theme.RemotexTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReleaseCriticalUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun secureTokenStoreRoundTripsAndClears() {
        val suffix = System.nanoTime().toString()
        val store = SecureTokenStore(
            context = InstrumentationRegistry.getInstrumentation().targetContext,
            scopeKey = suffix,
            preferencesName = "remotex.auth.test.$suffix",
            keyAlias = "app.remotex.relay-token.test.$suffix",
        )

        try {
            store.save("relay-secret")
            org.junit.Assert.assertEquals("relay-secret", store.load())
            store.clear()
            org.junit.Assert.assertEquals("", store.load())
        } finally {
            store.clear()
        }
    }

    @Test
    fun activeSessionRestoreStateIsScopedPerRelayAndClearable() {
        val suffix = System.nanoTime().toString()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferenceName = "remotex.session.test.$suffix"
        val first = ActiveSessionStore(context, "relay-a", preferenceName)
        val second = ActiveSessionStore(context, "relay-b", preferenceName)
        val expected = ActiveSession("session-1", "host-1", "thread-1", 42L)

        try {
            first.save(expected)
            org.junit.Assert.assertEquals(expected, first.load())
            org.junit.Assert.assertNull(second.load())
            first.clear()
            org.junit.Assert.assertNull(first.load())
        } finally {
            first.clear()
            second.clear()
            context.getSharedPreferences(preferenceName, android.content.Context.MODE_PRIVATE)
                .edit().clear().commit()
        }
    }

    @Test
    fun accessTokenIsAPasswordField() {
        compose.setContent {
            RemotexTheme {
                TokenField("relay-secret") {}
            }
        }

        compose.onNode(hasSetTextAction()).assert(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Password),
        )
        compose.onNodeWithContentDescription("Show access token").assertExists()
    }

    @Test
    fun secretAnswerIsAPasswordField() {
        compose.setContent {
            RemotexTheme {
                UserInputDialog(
                    prompt = UserInputPrompt(
                        callId = "secret-call",
                        questions = listOf(
                            UserInputQuestion(
                                id = "password",
                                question = "Enter secret",
                                isSecret = true,
                            ),
                        ),
                    ),
                    onSubmit = {},
                    onCancel = {},
                )
            }
        }

        compose.onNode(hasSetTextAction()).assert(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Password),
        )
    }

    @Test
    fun approvalDialogOnlyOffersAuthoritativeDecisions() {
        compose.setContent {
            RemotexTheme {
                ApprovalDialog(
                    prompt = ApprovalPrompt(
                        approvalId = "approval-1",
                        kind = "command",
                        reason = null,
                        command = "do thing",
                        cwd = null,
                        decisions = listOf("cancel"),
                    ),
                    onDecision = {},
                )
            }
        }

        compose.onNodeWithText("cancel").assertExists()
        compose.onNodeWithText("accept").assertDoesNotExist()
        compose.onNodeWithText("always").assertDoesNotExist()
        compose.onNodeWithText("decline").assertDoesNotExist()
    }

    @Test
    fun blankSetupBarHidesSessionControls() {
        compose.setContent {
            RemotexTheme {
                RemotexBar(
                    state = UiState(),
                    onBack = {},
                    onModelChange = {},
                    onEffortChange = {},
                )
            }
        }

        compose.onNodeWithText("REMOTEX").assertExists()
        compose.onNodeWithContentDescription("Host telemetry").assertDoesNotExist()
        compose.onNodeWithText("medium").assertDoesNotExist()
    }

    @Test
    fun pendingComposerKeepsStopAndShowsQueuedTurns() {
        compose.setContent {
            RemotexTheme {
                ComposerBar(
                    connected = true,
                    pending = true,
                    planMode = false,
                    pendingImages = emptyList(),
                    queuedTurns = listOf(
                        QueuedTurn(
                            id = "queued-1",
                            clientMessageId = "msg-1",
                            text = "next request",
                            model = "",
                            effort = "",
                            permissions = PermissionsMode.Default,
                        ),
                    ),
                    onSend = {},
                    onStop = {},
                    onAttachImage = {},
                    onRemoveImage = {},
                )
            }
        }

        compose.onNodeWithContentDescription("Stop").assertExists()
        compose.onNodeWithText("next (1)").assertExists()
        compose.onNodeWithText("next request").assertExists()
        compose.onNodeWithContentDescription("Remove queued turn").assertExists()
    }
}
