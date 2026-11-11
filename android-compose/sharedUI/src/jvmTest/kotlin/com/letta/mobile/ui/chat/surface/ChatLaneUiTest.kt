@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import com.letta.mobile.data.runtime.RuntimeLiveStatus
import com.letta.mobile.data.runtime.LoopPhase
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import com.letta.mobile.ui.chat.surface.status.RunStatusTestTags
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.assertSame
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.view.RuntimeViewId
import kotlin.test.Test
import kotlin.test.assertEquals

/** Rendered lane parity against the pre-lane column, not against a production ordering helper. */
class ChatLaneUiTest {
    @Test
    fun composerSurvivesOptionalModuleChangesOnPhone() = composerContinuity(360)

    @Test
    fun composerSurvivesOptionalModuleChangesInWideWindow() = composerContinuity(1200)

    private fun composerContinuity(width: Int) = runDesktopComposeUiTest(width = width, height = 800) {
        val default: List<Pair<RuntimeViewId, String?>> =
            listOf("chat.transcript", "chat.status", "chat.composer").map { RuntimeViewId(it) to null }
        var lane by mutableStateOf(default)
        var draft by mutableStateOf(TextFieldValue())
        var identity: Any? = null
        var disposals = 0
        setPointerContent {
            CompositionLocalProvider(LocalChatLane provides lane) {
                ChatLane(
                    modifier = Modifier.size(width.dp, 600.dp),
                    transcript = { Box(it) },
                    status = {},
                    composer = { modifier, _ ->
                        val instance = remember { Any() }
                        SideEffect { identity = instance }
                        DisposableEffect(instance) { onDispose { disposals++ } }
                        BasicTextField(draft, { draft = it }, modifier.testTag("continuity-composer"))
                    },
                )
            }
        }
        onNodeWithTag("continuity-composer").performClick().performTextInput("hello world")
        onNodeWithTag("continuity-composer").performTextInputSelection(TextRange(3, 5))
        waitForIdle()
        val originalIdentity = identity
        val originalNode = onNodeWithTag("continuity-composer").fetchSemanticsNode().id
        val selection = draft.selection
        val withCanvas = listOf(RuntimeViewId("shell.canvas") to "board-a") + default
        listOf(withCanvas, default).forEach { changed ->
            runOnIdle { lane = changed }
            waitForIdle()
            assertEquals(0, disposals, "layout change disposed the composer at $width dp")
            assertSame(originalIdentity, identity)
            assertEquals(originalNode, onNodeWithTag("continuity-composer").fetchSemanticsNode().id)
            onNodeWithTag("continuity-composer").assertIsFocused()
            assertEquals("hello world", draft.text)
            assertEquals(selection, draft.selection)
        }
    }

    @Test
    fun defaultDockDoesNotShowAttachedStatus() = dockedStatus(explicit = false)

    @Test
    fun explicitlyPositionedStatusShowsAboveDockedTranscript() = dockedStatus(explicit = true)

    @Test
    fun actualDockKeepsDefaultLiveStatusHidden() = actualDockedStatus(explicit = false)

    @Test
    fun actualDockBindsExplicitLiveStatusToItsSession() = actualDockedStatus(explicit = true)

    private fun actualDockedStatus(explicit: Boolean) = runDesktopComposeUiTest(width = 1200, height = 800) {
        val port = object : ChatSessionPort {
            override val uiState = MutableStateFlow(
                ChatUiState(conversationState = ConversationState.Ready("conv-status"), isLoadingMessages = false),
            )
            override val composer = MutableStateFlow(ChatComposerUiState(canSend = true))
            override val actions = RecordingChatActions()
            override val liveStatus = MutableStateFlow(RuntimeLiveStatus(phase = LoopPhase.WaitingForResponse))
        }
        val order = if (explicit) {
            listOf("chat.status", "chat.transcript", "chat.composer")
        } else {
            listOf("chat.transcript", "chat.status", "chat.composer")
        }
        setPointerContent {
            MaterialTheme {
                CompositionLocalProvider(LocalChatLane provides order.map { RuntimeViewId(it) to null }) {
                    ChatSurface(
                        port = port,
                        presentation = ChatSurfacePresentation.CanvasFirst,
                        onIntent = {},
                        host = ChatSurfaceHost(),
                        canvas = { Box(Modifier.fillMaxSize()) },
                    )
                }
            }
        }
        waitForIdle()
        if (explicit) {
            onNodeWithTag(RunStatusTestTags.LINE).assertExists()
        } else {
            onNodeWithTag(RunStatusTestTags.LINE).assertDoesNotExist()
        }
    }

    private fun dockedStatus(explicit: Boolean) = runDesktopComposeUiTest {
        val order = if (explicit) {
            listOf("chat.status", "chat.transcript", "chat.composer")
        } else {
            listOf("chat.transcript", "chat.status", "chat.composer")
        }
        setPointerContent {
            CompositionLocalProvider(LocalChatLane provides order.map { RuntimeViewId(it) to null }) {
                ChatLane(
                    modifier = Modifier.size(360.dp, 600.dp),
                    docked = true,
                    transcript = { Box(it.testTag("docked-transcript")) },
                    status = { Box(Modifier.fillMaxWidth().height(12.dp).testTag("docked-status")) },
                    composer = { _, _ -> error("the frame owns docked input") },
                )
            }
        }
        if (explicit) {
            val status = onNodeWithTag("docked-status").getUnclippedBoundsInRoot()
            val transcript = onNodeWithTag("docked-transcript").getUnclippedBoundsInRoot()
            assertEquals(status.bottom, transcript.top)
        } else {
            onNodeWithTag("docked-status").assertDoesNotExist()
        }
    }

    @Test
    fun defaultFullPageMatchesLegacyAtAllWidths() {
        listOf(360, 700, 760, 1200).forEach { width -> parity(width, docked = false) }
    }

    @Test
    fun defaultDockMatchesLegacyAtAllWidths() {
        listOf(360, 700, 760, 1200).forEach { width -> parity(width, docked = true) }
    }

    private fun parity(width: Int, docked: Boolean) = runDesktopComposeUiTest(width = width, height = 800) {
        setPointerContent {
            Column {
                Box(Modifier.size(width.dp, 320.dp).testTag("legacy")) { Legacy(docked) }
                Box(Modifier.size(width.dp, 320.dp).testTag("lane")) {
                    ChatLane(
                        modifier = Modifier.fillMaxSize(),
                        docked = docked,
                        transcript = { Block(it, Color.Blue) },
                        status = { Block(Modifier.fillMaxWidth().height(12.dp), Color.Red) },
                        composer = { modifier, attached -> ComposerBlocks(modifier, attached) },
                    )
                }
            }
        }
        waitForIdle()
        val expected = onNodeWithTag("legacy").captureToImage().toPixelMap()
        val actual = onNodeWithTag("lane").captureToImage().toPixelMap()
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)
        for (y in 0 until expected.height) {
            for (x in 0 until expected.width) {
                assertEquals(expected[x, y].toArgb(), actual[x, y].toArgb(), "pixel $x,$y at $width dp docked=$docked")
            }
        }
    }

    @Test
    fun reorderingAndHidingAStatusChangesTheRenderedOrder() = runDesktopComposeUiTest {
        setPointerContent {
            CompositionLocalProvider(
                LocalChatLane provides listOf(RuntimeViewId("chat.composer") to null, RuntimeViewId("chat.transcript") to null),
            ) {
                Box(Modifier.size(360.dp, 320.dp).testTag("reordered")) {
                    ChatLane(
                        modifier = Modifier.fillMaxSize(),
                        transcript = { Block(it, Color.Blue) },
                        status = { error("hidden status rendered") },
                        composer = { modifier, attached -> ComposerBlocks(modifier, attached) },
                    )
                }
            }
        }
        val pixels = onNodeWithTag("reordered").captureToImage().toPixelMap()
        assertEquals(Color.Green.toArgb(), pixels[0, 0].toArgb())
        assertEquals(Color.Blue.toArgb(), pixels[0, pixels.height - 1].toArgb())
    }
}

@Composable
private fun Legacy(docked: Boolean) {
    Column(Modifier.fillMaxSize()) {
        Block(Modifier.weight(1f).fillMaxWidth(), Color.Blue)
        if (!docked) ComposerBlocks(Modifier.fillMaxWidth(), attached = true)
    }
}

@Composable
private fun ComposerBlocks(modifier: Modifier, attached: Boolean) {
    Column(modifier) {
        if (attached) Block(Modifier.fillMaxWidth().height(12.dp), Color.Red)
        Block(Modifier.fillMaxWidth().height(48.dp), Color.Green)
    }
}

@Composable
private fun Block(modifier: Modifier, color: Color) {
    Box(modifier.background(color))
}
