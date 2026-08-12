package app.remotex.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.remotex.model.Host
import app.remotex.model.ThreadInfo
import app.remotex.security.SecureTokenStore
import app.remotex.persistence.ActiveSession
import app.remotex.persistence.ActiveSessionStore
import app.remotex.ui.components.TokenField
import app.remotex.ui.app.RemotexBar
import app.remotex.ui.screens.hosts.HostsScreen
import app.remotex.ui.screens.session.ApprovalDialog
import app.remotex.ui.screens.session.SessionScreen
import app.remotex.ui.screens.session.SessionSideRail
import app.remotex.ui.screens.session.UserInputDialog
import app.remotex.ui.screens.session.composer.ComposerBar
import app.remotex.ui.screens.threads.ThreadsScreen
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
                TokenField(value = "relay-secret", onChange = {})
            }
        }

        compose.onNode(hasSetTextAction()).assert(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Password),
        )
        compose.onNodeWithText("Access token").assertExists()
        compose.onNodeWithText(
            "Issued by your relay administrator and encrypted on this device.",
        ).assertExists()
        compose.onNodeWithContentDescription("Show access token").assertExists()
    }

    @Test
    fun relayConnectionRequiresAnAddressAndAccessToken() {
        compose.setContent {
            RemotexTheme {
                HostsScreen(
                    state = UiState(),
                    relayUrl = "https://relay.example.com",
                    onRelayUrlChange = {},
                    onTokenChange = {},
                    onRefresh = {},
                    onHostTap = {},
                    onModelChange = {},
                    onEffortChange = {},
                )
            }
        }

        compose.onNodeWithText("Relay address").assertExists()
        compose.onNodeWithText("Connect").assertIsNotEnabled()
    }

    @Test
    fun tabletHostsScreenUsesSeparateConnectionAndInventoryPanes() {
        compose.setContent {
            RemotexTheme {
                Box(Modifier.requiredSize(800.dp, 600.dp)) {
                    HostsScreen(
                        state = UiState(
                            userToken = "relay-secret",
                            hosts = listOf(
                                Host(
                                    id = "host-1",
                                    nickname = "Workstation",
                                    hostname = "studio-pc",
                                    platform = "linux",
                                    online = true,
                                ),
                            ),
                        ),
                        relayUrl = "https://relay.example.com",
                        onRelayUrlChange = {},
                        onTokenChange = {},
                        onRefresh = {},
                        onHostTap = {},
                        onModelChange = {},
                        onEffortChange = {},
                    )
                }
            }
        }

        // Connected tablets collapse the credential form to a summary, same
        // as phones; the full form is one tap away behind "settings →".
        compose.onNodeWithText("RELAY CONNECTED").assertExists()
        compose.onNodeWithText("Refresh hosts").assertDoesNotExist()
        compose.onNodeWithText("settings →").performClick()
        compose.onNodeWithText("Refresh hosts").assertIsEnabled()
        compose.onNodeWithContentDescription("Relay address")
            .performTextReplacement("not a relay address")
        compose.onNodeWithText("Refresh hosts").assertIsNotEnabled()
        val connection = compose.onNodeWithTag("connection-pane").fetchSemanticsNode().boundsInRoot
        val inventory = compose.onNodeWithTag("hosts-pane").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(connection.right < inventory.right)
        org.junit.Assert.assertTrue(connection.center.x < inventory.center.x)
        compose.onNodeWithText("studio-pc · linux · online", substring = true).assertExists()
    }

    @Test
    fun configuredPhoneCollapsesCredentialFormBehindConnectionSettings() {
        compose.setContent {
            RemotexTheme {
                Box(Modifier.requiredSize(400.dp, 760.dp)) {
                    HostsScreen(
                        state = UiState(
                            userToken = "relay-secret",
                            hosts = listOf(
                                Host(
                                    id = "host-1",
                                    nickname = "Workstation",
                                    hostname = "studio-pc",
                                    platform = "linux",
                                    online = true,
                                ),
                            ),
                        ),
                        relayUrl = "https://relay.example.com",
                        onRelayUrlChange = {},
                        onTokenChange = {},
                        onRefresh = {},
                        onHostTap = {},
                        onModelChange = {},
                        onEffortChange = {},
                    )
                }
            }
        }

        compose.onNodeWithText("RELAY CONNECTED").assertExists()
        compose.onNodeWithText("settings →").assertExists()
        compose.onNodeWithText("Access token").assertDoesNotExist()
        compose.onNodeWithText("studio-pc · linux · online", substring = true).assertExists()
    }

    @Test
    fun tabletThreadsScreenSeparatesActionsFromSavedSessions() {
        compose.setContent {
            RemotexTheme {
                Box(Modifier.requiredSize(800.dp, 600.dp)) {
                    ThreadsScreen(
                        state = UiState(
                            selectedHostId = "host-1",
                            hosts = listOf(
                                Host(
                                    id = "host-1",
                                    nickname = "Workstation",
                                    hostname = "studio-pc",
                                    online = true,
                                ),
                            ),
                        ),
                        onRefresh = {},
                        onNewSession = {},
                        onResumeThread = {},
                    )
                }
            }
        }

        val actions = compose.onNodeWithTag("session-actions-pane").fetchSemanticsNode().boundsInRoot
        val sessions = compose.onNodeWithTag("saved-sessions-pane").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(actions.center.x < sessions.center.x)
        compose.onNodeWithText("New session").assertExists()
        compose.onNodeWithText("Previous sessions").assertExists()
    }

    @Test
    fun expandedSessionKeepsChatAtReadableWidth() {
        compose.setContent {
            RemotexTheme {
                Box(Modifier.requiredSize(1200.dp, 700.dp)) {
                    SessionScreen(
                        state = UiState(),
                        onSend = {},
                        onStop = {},
                        onSteer = {},
                        onQueue = {},
                        onRemoveQueued = {},
                        onLoadOlder = {},
                        onAttachImage = {},
                        onRemoveImage = {},
                        onPermissionsChange = {},
                        onSlashCommand = { _, _ -> },
                        onListWorkspace = { emptyList() },
                        onDeleteWorkspaceFile = {},
                        onRenameWorkspaceFile = { _, _ -> },
                        onReadWorkspaceFile = { error("unused") },
                        onUploadWorkspaceFile = { _, _, _, _ -> },
                    )
                }
            }
        }

        val width = compose.onNodeWithTag("session-content").fetchSemanticsNode().boundsInRoot.width
        val maxWidth = with(compose.density) { 840.dp.toPx() }
        org.junit.Assert.assertTrue("session width was $width px", width <= maxWidth + 1f)
    }

    @Test
    fun sessionSideRailListsHistoryAndSwitchesToTelemetry() {
        var split: String? = null
        compose.setContent {
            RemotexTheme {
                Box(Modifier.requiredSize(340.dp, 700.dp)) {
                    SessionSideRail(
                        threads = listOf(ThreadInfo(id = "t1", preview = "fix the relay bug")),
                        threadsLoading = false,
                        activeThreadId = "t1",
                        splitEnabled = true,
                        hostLabel = "Workstation",
                        snapshot = null,
                        onRefreshThreads = {},
                        onOpenThread = {},
                        onSplitThread = { split = it.id },
                    )
                }
            }
        }

        compose.onNodeWithText("fix the relay bug", substring = true).assertExists()
        compose.onNodeWithContentDescription("Open in split view").performClick()
        org.junit.Assert.assertEquals("t1", split)
        compose.onNodeWithText("system").performClick()
        compose.onNodeWithText("SYSTEM TELEMETRY").assertExists()
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
    fun compactBarMovesPickersOutOfTheCrowdedHeader() {
        compose.setContent {
            RemotexTheme {
                Box(Modifier.requiredSize(400.dp, 80.dp)) {
                    RemotexBar(
                        state = UiState(
                            screen = Screen.Threads,
                            selectedHostId = "host-1",
                        ),
                        onBack = {},
                        onModelChange = {},
                        onEffortChange = {},
                    )
                }
            }
        }

        compose.onNodeWithText("REMOTEX").assertExists()
        compose.onNodeWithText("ready").assertExists()
        compose.onNodeWithText("medium").assertDoesNotExist()
        compose.onNodeWithContentDescription("Host telemetry").assertExists()
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
            .assertWidthIsAtLeast(44.dp)
            .assertHeightIsAtLeast(44.dp)
    }
}
