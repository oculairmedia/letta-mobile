@file:OptIn(ExperimentalTestApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
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
import com.letta.mobile.data.repository.modelcontrol.ModelPickerCatalog
import com.letta.mobile.data.repository.modelcontrol.ReasoningEffortChoice
import com.letta.mobile.ui.chat.ChatColumnMaxWidth
import com.letta.mobile.ui.chat.QueuedSendsPanelTestTags
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.session.ChatComposerCommand
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatModelOption
import com.letta.mobile.ui.chat.session.ChatModelUiState
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfaceIntent
import com.letta.mobile.ui.chat.session.ChatSurfaceMode
import com.letta.mobile.ui.chat.session.ChatWorkingDirectoryUiState
import com.letta.mobile.ui.chat.surface.ChatSurfacePlatform
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import com.letta.mobile.ui.modelcontrol.ModelPickerTags
import com.letta.mobile.ui.modelcontrol.RecordedPicks
import com.letta.mobile.ui.modelcontrol.pickerModel
import com.letta.mobile.ui.modelcontrol.testModelPicker
import kotlinx.collections.immutable.persistentListOf
import java.awt.datatransfer.StringSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.letta.mobile.ui.chat.surface.setPointerContent

/**
 * The shared composer's UI behaviour. The first cases are ported from desktop's
 * DesktopChatInteractionUiTest; the rest cover what the shared panel adds (modes, the queue,
 * autocomplete routing through ChatActions).
 */
class ChatComposerPanelUiTest {
    @Composable
    private fun Panel(inputs: ComposerInputs) {
        MaterialTheme {
            ChatComposerPanel(inputs)
        }
    }

    private fun ready(text: String = "ready") = ChatComposerUiState(
        text = text,
        canSend = true,
        model = ChatModelUiState(currentHandle = null, currentLabel = "Model"),
    )

    @Test
    fun binaryTextIsRejectedAtTheComposerField() = runComposeUiTest {
        var text by mutableStateOf("draft")
        val actions = RecordingChatActions(onText = { text = it })
        setPointerContent { Panel(ComposerInputs(composer = ready(text), actions = actions)) }
        val rawHeader = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
            0, 0, 0, 0x0d, 0x49, 0x48, 0x44, 0x52,
        ).decodeToString()
        val input = onNodeWithTag(ComposerTestTags.INPUT).requestFocus()
        listOf("PNG\n\u001a", rawHeader).forEachIndexed { index, binary ->
            input.performTextInput(binary)
            runOnIdle {
                assertEquals("draft", text)
                assertEquals(index + 1, actions.composerErrors.size)
                assertEquals(
                    "Could not attach that image: the clipboard did not contain an image the app could read",
                    actions.composerErrors.last(),
                )
            }
            input.assertTextEquals("draft")
        }
    }

    @Test
    fun ordinaryMultilingualTextRemainsEditable() = runComposeUiTest {
        var text by mutableStateOf("")
        val actions = RecordingChatActions(onText = { text = it })
        setPointerContent { Panel(ComposerInputs(composer = ready(text), actions = actions)) }
        val input = onNodeWithTag(ComposerTestTags.INPUT).requestFocus()
        val legitimate = listOf("PNG", "\nsecond line", "\ttab", "\r\nCRLF", " 🙂 世界")
        legitimate.forEach { input.performTextInput(it) }
        runOnIdle {
            assertEquals(legitimate.joinToString(""), text)
            assertEquals(0, actions.count("reportComposerError"))
        }
    }

    @Test
    fun imageSignaturesAndForbiddenControlsAreRejected() = runComposeUiTest {
        var text by mutableStateOf("draft")
        val actions = RecordingChatActions(onText = { text = it })
        setPointerContent { Panel(ComposerInputs(composer = ready(text), actions = actions)) }
        val input = onNodeWithTag(ComposerTestTags.INPUT).requestFocus()
        val signatures = listOf("\ufffdPNG", "\u00ff\u00d8\u00ff", "\ufffd\ufffd\ufffd", "GIF87a", "GIF89a", "RIFF1234WEBP", "BM")
        val controls = (0..31).filterNot { it == 9 || it == 10 || it == 13 }.map { it.toChar().toString() } + "\u007f"
        (signatures + controls).forEachIndexed { index, value ->
            input.performTextInput(value)
            runOnIdle {
                assertEquals("draft", text, "accepted $value")
                assertEquals(index + 1, actions.composerErrors.size, "no error for $value")
            }
        }
        input.assertTextEquals("draft")
    }

    @Test
    fun keyboardPasteRejectsUnreadableClipboardText() = runComposeUiTest {
        var text by mutableStateOf("draft")
        val actions = RecordingChatActions(onText = { text = it })
        val clipboard = object : Clipboard {
            override suspend fun getClipEntry(): ClipEntry = ClipEntry(StringSelection("PNG\n\u001a"))
            override suspend fun setClipEntry(clipEntry: ClipEntry?) = Unit
        }
        setPointerContent {
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                Panel(ComposerInputs(composer = ready(text), actions = actions))
            }
        }
        onNodeWithTag(ComposerTestTags.INPUT).requestFocus().performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.V)
            keyUp(Key.CtrlLeft)
        }
        runOnIdle {
            assertEquals("draft", text)
            assertEquals(1, actions.composerErrors.size)
        }
        onNodeWithTag(ComposerTestTags.INPUT).assertTextEquals("draft")
    }

    @Test
    fun externalComposerResetMovesCaretToEnd() = runComposeUiTest {
        var text by mutableStateOf("draft")
        val actions = RecordingChatActions(onText = { text = it })
        setPointerContent { Panel(ComposerInputs(composer = ready(text), actions = actions)) }

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
        setPointerContent { Box(Modifier.width(420.dp)) { Panel(ComposerInputs(composer = ready(), actions = actions)) } }

        onNodeWithContentDescription("Attach").assertExists()
        onNodeWithText("Model").assertExists()
        onNodeWithTag(ComposerTestTags.SEND).assertExists().assertIsEnabled().performClick()
        runOnIdle { assertEquals(1, actions.count("send")) }
    }

    @Test
    fun keyboardHintsShowWhereThePlatformAsksForThem() = runComposeUiTest {
        setPointerContent { Panel(ComposerInputs(composer = ready(text = ""), actions = RecordingChatActions())) }
        assertEquals(1, onAllNodesWithTag(ComposerTestTags.HINT, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun touchHostsHideTheKeyboardHints() = runComposeUiTest {
        setPointerContent {
            Panel(
                ComposerInputs(
                    composer = ready(text = ""),
                    actions = RecordingChatActions(),
                    platform = ChatSurfacePlatform(showKeyboardHints = false),
                ),
            )
        }
        assertEquals(0, onAllNodesWithTag(ComposerTestTags.HINT, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun sendIsDisabledWithoutAPayload() = runComposeUiTest {
        setPointerContent { Panel(ComposerInputs(composer = ready(text = ""), actions = RecordingChatActions())) }
        onNodeWithTag(ComposerTestTags.SEND).assertIsNotEnabled()
    }

    @Test
    fun composerStopsGrowingOnAWideWindow() {
        val wide = 1600.dp
        var measured: Dp = Dp.Unspecified
        runComposeUiTest {
            setPointerContent { Box(Modifier.width(wide)) { Panel(ComposerInputs(composer = ready(), actions = RecordingChatActions())) } }
            measured = onNodeWithTag(ComposerTestTags.CONTROLS).getUnclippedBoundsInRoot().width
        }
        assertTrue(measured <= ChatColumnMaxWidth, "composer spanned $measured of a $wide window")
    }

    @Test
    fun stopButtonStopsTheRunWhileStreaming() = runComposeUiTest {
        val actions = RecordingChatActions()
        setPointerContent {
            Panel(ComposerInputs(composer = ready(text = ""), actions = actions, uiState = ChatUiState(isStreaming = true)))
        }
        onNodeWithTag(ComposerTestTags.SEND).assertDoesNotExist()
        onNodeWithTag(ComposerTestTags.STOP).performClick()
        runOnIdle { assertEquals(1, actions.count("stopRun")) }
    }

    @Test
    fun swipeUpOnTheFullScreenCardOpensTheCanvas() = runComposeUiTest {
        val intents = mutableListOf<ChatSurfaceIntent>()
        setPointerContent {
            Panel(
                ComposerInputs(
                    composer = ready(),
                    actions = RecordingChatActions(),
                    onIntent = { intents += it },
                    host = ChatSurfaceHost(openCanvas = {}),
                ),
            )
        }

        onNodeWithTag(ComposerTestTags.CARD).performTouchInput { swipeUp() }
        runOnIdle { assertEquals(listOf<ChatSurfaceIntent>(ChatSurfaceIntent.OpenCanvas), intents) }
    }

    @Test
    fun swipeUpDoesNothingWhenTheHostHasNoCanvas() = runComposeUiTest {
        val intents = mutableListOf<ChatSurfaceIntent>()
        setPointerContent { Panel(ComposerInputs(composer = ready(), actions = RecordingChatActions(), onIntent = { intents += it })) }

        onNodeWithTag(ComposerTestTags.CARD).performTouchInput { swipeUp() }
        runOnIdle { assertTrue(intents.isEmpty(), "got $intents") }
    }

    @Test
    fun swipeUpDoesNothingWhileARunStreams() = runComposeUiTest {
        val intents = mutableListOf<ChatSurfaceIntent>()
        setPointerContent {
            Panel(
                ComposerInputs(
                    composer = ready(),
                    actions = RecordingChatActions(),
                    uiState = ChatUiState(isStreaming = true),
                    onIntent = { intents += it },
                ),
            )
        }

        onNodeWithTag(ComposerTestTags.CARD).performTouchInput { swipeUp() }
        runOnIdle { assertTrue(intents.isEmpty(), "got $intents") }
    }

    @Test
    fun dockedBarExpandsAndSendsTheSameDraft() = runComposeUiTest {
        val intents = mutableListOf<ChatSurfaceIntent>()
        val actions = RecordingChatActions()
        setPointerContent {
            Panel(ComposerInputs(composer = ready(), actions = actions, mode = ChatSurfaceMode.Docked, onIntent = { intents += it }))
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
        setPointerContent { Panel(ComposerInputs(composer = ready(text).copy(commands = persistentListOf(command)), actions = actions)) }

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
        setPointerContent { Panel(ComposerInputs(composer = ready("/new").copy(commands = persistentListOf(command)), actions = actions)) }

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
        setPointerContent { Panel(ComposerInputs(composer = ready(text).copy(commands = persistentListOf(skill)), actions = actions)) }

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
        setPointerContent {
            Panel(ComposerInputs(composer = ready(text).copy(mentionables = persistentListOf(mention)), actions = actions))
        }

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
        setPointerContent { Panel(ComposerInputs(composer = ready(), actions = actions, uiState = ChatUiState(sendQueue = queue))) }

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
        setPointerContent { Panel(ComposerInputs(composer = ready().copy(error = "Image too large"), actions = actions)) }

        onNodeWithText("Image too large").assertExists()
        onNodeWithContentDescription("Dismiss").performClick()
        runOnIdle { assertEquals(1, actions.count("clearComposerError")) }
    }

    @Test
    fun modelChipOpensTheSharedPickerAndHidesWithoutTheCapability() = runComposeUiTest {
        val picker = testModelPicker()
        var capabilities by mutableStateOf(ChatSurfaceCapabilities.Default)
        setPointerContent {
            Panel(
                ComposerInputs(
                    composer = ready(),
                    actions = RecordingChatActions(),
                    capabilities = capabilities,
                    host = ChatSurfaceHost(modelPicker = picker),
                ),
            )
        }

        onNodeWithTag(ComposerTestTags.MODEL_CHIP).performClick()
        runOnIdle { assertTrue(picker.pickerOpen) }
        onNodeWithTag(ModelPickerTags.PANE).assertExists()
        picker.close()
        capabilities = ChatSurfaceCapabilities(modelSwitch = false)
        onNodeWithTag(ComposerTestTags.MODEL_CHIP).assertDoesNotExist()
    }

    @Test
    fun withoutAHostPickerTheChipOpensNothing() = runComposeUiTest {
        setPointerContent { Panel(ComposerInputs(composer = ready(), actions = RecordingChatActions(), host = ChatSurfaceHost())) }

        onNodeWithTag(ComposerTestTags.MODEL_CHIP).assertIsNotEnabled()
        onNodeWithTag(ModelPickerTags.PANE).assertDoesNotExist()
    }

    @Test
    fun sharedPickerListsRecentsAndProvidersAndPicksOnceThroughTheHost() = runComposeUiTest {
        val model = ChatModelUiState(
            currentHandle = "openai/a",
            currentLabel = "Model A",
            options = persistentListOf(
                ChatModelOption(handle = "openai/a", label = "Model A", provider = "OpenAI", reasoningEfforts = persistentListOf("low", "high")),
                ChatModelOption(handle = "anthropic/b", label = "Model B", provider = "Anthropic"),
            ),
        )
        val picks = RecordedPicks()
        val picker = testModelPicker(
            models = listOf(pickerModel("openai/a", "Model A"), pickerModel("anthropic/b", "Model B")),
            picks = picks,
            recents = listOf("anthropic/b"),
        )
        val actions = RecordingChatActions()
        val inputs = ComposerInputs(composer = ready().copy(model = model), actions = actions, host = ChatSurfaceHost(modelPicker = picker))
        setPointerContent { Panel(inputs) }

        onNodeWithTag(ComposerTestTags.MODEL_CHIP).performClick()
        onNodeWithTag(ModelPickerTags.PANE).assertExists()
        onNodeWithTag(ModelPickerTags.GROUP_PREFIX + ModelPickerCatalog.RECENTS_GROUP_KEY).assertExists()
        onNodeWithTag(ModelPickerTags.REFRESH).assertExists()
        // "Model B" is listed under Recent and under its provider: the first pick closes the picker.
        onAllNodesWithTag(ModelPickerTags.ROW_PREFIX + "anthropic/b")[0].performClick()
        runOnIdle {
            assertEquals(listOf("anthropic/b"), picks.models)
            assertFalse(picker.pickerOpen)
        }
        onNodeWithTag(ModelPickerTags.PANE).assertDoesNotExist()
        onNodeWithTag(ComposerTestTags.EFFORT_CHIP).performClick()
        onNodeWithText("High").performClick()
        val expected: List<Pair<String, ReasoningEffortChoice>> = listOf("openai/a" to ReasoningEffortChoice.Named("high"))
        runOnIdle { assertEquals(expected, actions.models) }
    }

    @Test
    fun workingDirectoryShowsOnlyWithTheCapabilityAndPicksThroughTheHost() = runComposeUiTest {
        var picks = 0
        var capabilities by mutableStateOf(ChatSurfaceCapabilities(workingDirectory = true))
        setPointerContent {
            Panel(
                ComposerInputs(
                    composer = ready().copy(workingDirectory = ChatWorkingDirectoryUiState(path = "/repo")),
                    actions = RecordingChatActions(),
                    capabilities = capabilities,
                    host = ChatSurfaceHost(pickWorkingDirectory = { picks++ }),
                ),
            )
        }

        onNodeWithText("/repo").performClick()
        runOnIdle { assertEquals(1, picks) }
        capabilities = ChatSurfaceCapabilities(workingDirectory = false)
        onNodeWithTag(ComposerTestTags.WORKING_DIRECTORY).assertDoesNotExist()
    }

    @Test
    fun backgroundProcessShelfRendersAndInvokesStop() = runComposeUiTest {
        val actions = RecordingChatActions()
        val process = com.letta.mobile.data.transport.appserver.AppServerBackgroundProcess(
            id = "proc-1",
            type = "bash",
            description = "npm run build",
            ageSeconds = 42,
        )
        setPointerContent {
            Panel(
                ComposerInputs(
                    composer = ready().copy(backgroundProcesses = persistentListOf(process)),
                    actions = actions,
                ),
            )
        }

        onNodeWithTag("background_process_shelf").assertExists()
        onNodeWithText("npm run build").assertExists()
        onNodeWithContentDescription("Stop process proc-1").performClick()
        onNodeWithText("Stop").performClick()
        runOnIdle {
            assertEquals(listOf("proc-1"), actions.stoppedProcesses)
        }
    }
}
