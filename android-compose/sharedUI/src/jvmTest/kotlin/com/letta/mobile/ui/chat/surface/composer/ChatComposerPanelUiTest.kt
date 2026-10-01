@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.composer

import com.letta.mobile.ui.chat.surface.RecordingChatActions
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import com.letta.mobile.data.chat.send.ConversationSendQueue
import com.letta.mobile.data.chat.send.QueueConversationId
import com.letta.mobile.data.chat.send.QueuedChatSend
import com.letta.mobile.data.chat.send.QueuedSendId
import com.letta.mobile.data.composer.MentionKind
import com.letta.mobile.data.composer.Mentionable
import com.letta.mobile.ui.chat.ChatColumnMaxWidth
import com.letta.mobile.ui.chat.QueuedSendsPanelTestTags
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.session.ChatComposerCommand
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.data.repository.modelcontrol.ReasoningEffortChoice
import com.letta.mobile.ui.chat.session.ChatModelOption
import com.letta.mobile.ui.chat.session.ChatModelUiState
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfaceIntent
import com.letta.mobile.ui.chat.session.ChatSurfaceMode
import com.letta.mobile.ui.chat.session.ChatWorkingDirectoryUiState
import com.letta.mobile.ui.chat.surface.ChatSurfacePlatform
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shared composer's UI behaviour. The first cases are ported from desktop's
 * DesktopChatInteractionUiTest; the rest cover what the shared panel adds (modes, the queue,
 * autocomplete routing through ChatActions).
 */
class ChatComposerPanelUiTest {
    @Composable
    private fun Panel(
        composer: ChatComposerUiState,
        actions: RecordingChatActions,
        uiState: ChatUiState = ChatUiState(),
        mode: ChatSurfaceMode = ChatSurfaceMode.FullScreen,
        onIntent: (ChatSurfaceIntent) -> Unit = {},
        capabilities: ChatSurfaceCapabilities = ChatSurfaceCapabilities.Default,
        host: ChatSurfaceHost = ChatSurfaceHost(),
    ) {
        MaterialTheme {
            ChatComposerPanel(
                composer = composer,
                uiState = uiState,
                actions = actions,
                capabilities = capabilities,
                host = host,
                platform = ChatSurfacePlatform.Default,
                mode = mode,
                onIntent = onIntent,
            )
        }
    }

    private fun ready(text: String = "ready") = ChatComposerUiState(
        text = text,
        canSend = true,
        model = ChatModelUiState(currentHandle = null, currentLabel = "Model"),
    )

    @Test
    fun externalComposerResetMovesCaretToEnd() = runComposeUiTest {
        var text by mutableStateOf("draft")
        val actions = RecordingChatActions(onText = { text = it })
        setContent { Panel(composer = ready(text), actions = actions) }

        runOnIdle { text = "externally reset" }
        val input = onNodeWithTag(ComposerTestTags.INPUT)
        input.assertTextEquals("externally reset")
        input.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange("externally reset".length)),
        )
    }

    @Test
    fun narrowComposerKeepsEveryControlAndSendReachable() = runComposeUiTest {
        val actions = RecordingChatActions()
        setContent { Box(Modifier.width(420.dp)) { Panel(composer = ready(), actions = actions) } }

        onNodeWithContentDescription("Attach").assertExists()
        onNodeWithText("Model").assertExists()
        onNodeWithTag(ComposerTestTags.SEND).assertExists().assertIsEnabled().performClick()
        runOnIdle { assertEquals(1, actions.count("send")) }
    }

    @Test
    fun sendIsDisabledWithoutAPayload() = runComposeUiTest {
        setContent { Panel(composer = ready(text = ""), actions = RecordingChatActions()) }
        onNodeWithTag(ComposerTestTags.SEND).assertIsNotEnabled()
    }

    @Test
    fun composerStopsGrowingOnAWideWindow() {
        val wide = 1600.dp
        var measured: Dp = Dp.Unspecified
        runComposeUiTest {
            setContent { Box(Modifier.width(wide)) { Panel(composer = ready(), actions = RecordingChatActions()) } }
            measured = onNodeWithTag(ComposerTestTags.CONTROLS).getUnclippedBoundsInRoot().width
        }
        assertTrue(measured <= ChatColumnMaxWidth, "composer spanned $measured of a $wide window")
    }

    @Test
    fun stopButtonStopsTheRunWhileStreaming() = runComposeUiTest {
        val actions = RecordingChatActions()
        setContent {
            Panel(composer = ready(text = ""), actions = actions, uiState = ChatUiState(isStreaming = true))
        }
        onNodeWithTag(ComposerTestTags.SEND).assertDoesNotExist()
        onNodeWithTag(ComposerTestTags.STOP).performClick()
        runOnIdle { assertEquals(1, actions.count("stopRun")) }
    }

    @Test
    fun swipeUpOnTheFullScreenCardOpensTheCanvas() = runComposeUiTest {
        val intents = mutableListOf<ChatSurfaceIntent>()
        setContent {
            Panel(
                composer = ready(),
                actions = RecordingChatActions(),
                onIntent = { intents += it },
                host = ChatSurfaceHost(openCanvas = {}),
            )
        }

        onNodeWithTag(ComposerTestTags.CARD).performTouchInput { swipeUp() }
        runOnIdle { assertEquals(listOf<ChatSurfaceIntent>(ChatSurfaceIntent.OpenCanvas), intents) }
    }

    @Test
    fun swipeUpDoesNothingWhenTheHostHasNoCanvas() = runComposeUiTest {
        val intents = mutableListOf<ChatSurfaceIntent>()
        setContent { Panel(composer = ready(), actions = RecordingChatActions(), onIntent = { intents += it }) }

        onNodeWithTag(ComposerTestTags.CARD).performTouchInput { swipeUp() }
        runOnIdle { assertTrue(intents.isEmpty(), "got $intents") }
    }

    @Test
    fun swipeUpDoesNothingWhileARunStreams() = runComposeUiTest {
        val intents = mutableListOf<ChatSurfaceIntent>()
        setContent {
            Panel(
                composer = ready(),
                actions = RecordingChatActions(),
                uiState = ChatUiState(isStreaming = true),
                onIntent = { intents += it },
            )
        }

        onNodeWithTag(ComposerTestTags.CARD).performTouchInput { swipeUp() }
        runOnIdle { assertTrue(intents.isEmpty(), "got $intents") }
    }

    @Test
    fun dockedBarExpandsAndSendsTheSameDraft() = runComposeUiTest {
        val intents = mutableListOf<ChatSurfaceIntent>()
        val actions = RecordingChatActions()
        setContent {
            Panel(composer = ready(), actions = actions, mode = ChatSurfaceMode.Docked, onIntent = { intents += it })
        }

        onNodeWithTag(ComposerTestTags.CARD).assertDoesNotExist()
        onNodeWithTag(ComposerTestTags.DOCKED_INPUT).assertTextEquals("ready")
        onNodeWithTag(ComposerTestTags.EXPAND).performClick()
        onNodeWithTag(ComposerTestTags.SEND).performClick()
        runOnIdle {
            assertEquals(listOf<ChatSurfaceIntent>(ChatSurfaceIntent.Expand), intents)
            assertEquals(1, actions.count("send"))
        }
    }

    @Test
    fun slashActionCommandRunsInsteadOfSending() = runComposeUiTest {
        val command = ChatComposerCommand(id = "new", label = "new", description = "New chat")
        var text by mutableStateOf("/ne")
        val actions = RecordingChatActions(onText = { text = it })
        setContent { Panel(composer = ready(text).copy(commands = persistentListOf(command)), actions = actions) }

        onNodeWithTag(ComposerTestTags.COMMAND_ROW + "new").performClick()
        runOnIdle {
            assertEquals(listOf(command), actions.commandsRun)
            assertEquals("", text)
            assertEquals(0, actions.count("send"))
        }
    }

    @Test
    fun enterRunsTheTypedSlashActionCommand() = runComposeUiTest {
        val command = ChatComposerCommand(id = "new", label = "new")
        val actions = RecordingChatActions()
        setContent { Panel(composer = ready("/new").copy(commands = persistentListOf(command)), actions = actions) }

        onNodeWithTag(ComposerTestTags.INPUT).requestFocus().performKeyInput { pressKey(Key.Enter) }
        runOnIdle {
            assertEquals(listOf(command), actions.commandsRun)
            assertEquals(0, actions.count("send"))
        }
    }

    @Test
    fun slashFillCommandPutsItsTextInTheDraftAndRemovableOnesUninstall() = runComposeUiTest {
        val skill = ChatComposerCommand(id = "/review", label = "/review", fillsComposer = true, removable = true)
        var text by mutableStateOf("/rev")
        val actions = RecordingChatActions(onText = { text = it })
        setContent { Panel(composer = ready(text).copy(commands = persistentListOf(skill)), actions = actions) }

        onNodeWithContentDescription("More options for /review").performClick()
        onNodeWithText("Uninstall /review").performClick()
        runOnIdle { assertEquals(listOf(skill), actions.uninstalled) }

        onNodeWithTag(ComposerTestTags.COMMAND_ROW + "/review").performClick()
        runOnIdle {
            assertEquals("/review ", text)
            assertTrue(actions.commandsRun.isEmpty())
        }
    }

    @Test
    fun mentionSelectionInsertsTheMention() = runComposeUiTest {
        val mention = Mentionable(id = "f1", label = "main.kt", sublabel = "src", kind = MentionKind.File)
        var text by mutableStateOf("look at @ma")
        val actions = RecordingChatActions(onText = { text = it })
        setContent { Panel(composer = ready(text).copy(mentionables = persistentListOf(mention)), actions = actions) }

        onNodeWithText("main.kt").performClick()
        runOnIdle { assertEquals("look at @main.kt ", text) }
    }

    @Test
    fun queuePanelActionsRouteToChatActions() = runComposeUiTest {
        val conversation = QueueConversationId("c1")
        val queue = ConversationSendQueue(
            items = listOf(QueuedChatSend(QueuedSendId("q1"), conversation, "follow up")),
            paused = true,
        )
        val actions = RecordingChatActions()
        setContent { Panel(composer = ready(), actions = actions, uiState = ChatUiState(sendQueue = queue)) }

        onNodeWithTag(QueuedSendsPanelTestTags.CANCEL + "q1").performClick()
        onNodeWithTag(QueuedSendsPanelTestTags.SEND_NOW + "q1").performClick()
        onNodeWithTag(QueuedSendsPanelTestTags.RESUME).performClick()
        runOnIdle {
            assertTrue("cancelQueuedSend:q1" in actions.calls)
            assertTrue("sendQueuedNow:q1" in actions.calls)
            assertTrue("resumeSendQueue" in actions.calls)
        }
    }

    @Test
    fun composerErrorShowsInlineAndDismisses() = runComposeUiTest {
        val actions = RecordingChatActions()
        setContent { Panel(composer = ready().copy(error = "Image too large"), actions = actions) }

        onNodeWithText("Image too large").assertExists()
        onNodeWithContentDescription("Dismiss").performClick()
        runOnIdle { assertEquals(1, actions.count("clearComposerError")) }
    }

    @Test
    fun modelChipPrefersTheHostPickerAndHidesWithoutTheCapability() = runComposeUiTest {
        var hostPicker = 0
        var capabilities by mutableStateOf(ChatSurfaceCapabilities.Default)
        setContent {
            Panel(
                composer = ready(),
                actions = RecordingChatActions(),
                capabilities = capabilities,
                host = ChatSurfaceHost(openModelPicker = { hostPicker++ }),
            )
        }

        onNodeWithTag(ComposerTestTags.MODEL_CHIP).performClick()
        runOnIdle { assertEquals(1, hostPicker) }
        capabilities = ChatSurfaceCapabilities(modelSwitch = false)
        onNodeWithTag(ComposerTestTags.MODEL_CHIP).assertDoesNotExist()
    }

    @Test
    fun sharedModelSheetAndEffortChipSelectThroughChatActions() = runComposeUiTest {
        val model = ChatModelUiState(
            currentHandle = "openai/a",
            currentLabel = "Model A",
            options = persistentListOf(
                ChatModelOption(handle = "openai/a", label = "Model A", provider = "OpenAI", reasoningEfforts = persistentListOf("low", "high")),
                ChatModelOption(handle = "anthropic/b", label = "Model B", provider = "Anthropic"),
            ),
        )
        val actions = RecordingChatActions()
        setContent { Panel(composer = ready().copy(model = model), actions = actions) }

        onNodeWithTag(ComposerTestTags.MODEL_CHIP).performClick()
        onNodeWithTag(ComposerTestTags.MODEL_SHEET).assertExists()
        onNodeWithText("Model B").performClick()
        onNodeWithTag(ComposerTestTags.EFFORT_CHIP).performClick()
        onNodeWithText("high").performClick()
        runOnIdle {
            assertEquals(
                listOf(
                    "anthropic/b" to ReasoningEffortChoice.Unchanged,
                    "openai/a" to ReasoningEffortChoice.Named("high"),
                ),
                actions.models,
            )
        }
    }

    @Test
    fun workingDirectoryShowsOnlyWithTheCapabilityAndPicksThroughTheHost() = runComposeUiTest {
        var picks = 0
        var capabilities by mutableStateOf(ChatSurfaceCapabilities(workingDirectory = true))
        setContent {
            Panel(
                composer = ready().copy(workingDirectory = ChatWorkingDirectoryUiState(path = "/repo")),
                actions = RecordingChatActions(),
                capabilities = capabilities,
                host = ChatSurfaceHost(pickWorkingDirectory = { picks++ }),
            )
        }

        onNodeWithText("/repo").performClick()
        runOnIdle { assertEquals(1, picks) }
        capabilities = ChatSurfaceCapabilities(workingDirectory = false)
        onNodeWithTag(ComposerTestTags.WORKING_DIRECTORY).assertDoesNotExist()
    }
}
