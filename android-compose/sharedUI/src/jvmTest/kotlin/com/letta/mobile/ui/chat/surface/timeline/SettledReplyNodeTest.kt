@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.surface.ChatSurfaceAppearance
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import com.letta.mobile.ui.chat.surface.timeline.rows.LocalRunStepLifetime
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import kotlinx.collections.immutable.toImmutableList
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.ReasoningMessage
import com.letta.mobile.data.model.UsageStatistics
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A retained LazyColumn slot must also retain the reply's actual rendered node. */
class SettledReplyNodeTest {
    private val prompt = UserMessage(id = "prompt", contentRaw = JsonPrimitive("Check it"), date = DATE, otid = "prompt-otid")
    private val thought = ReasoningMessage(id = "stream-thought", reasoning = "Checking", runId = RUN, date = DATE)
    private val reply = AssistantMessage(id = "stream-reply", contentRaw = JsonPrimitive(TEXT), runId = RUN, date = DATE)

    @Test
    fun replyNodeSurvivesCanonicalSettlementAndLaterPagingGeneration() = verifyReplyNode(carriesLogicalIds = true)

    @Test
    fun settledAliasSurvivesLiveClearAndLaterDurableRevision() = verifyReplyNode(carriesLogicalIds = false)

    @Test
    fun aResidentPromptKeepsTheLiveReplyInItsRunThroughRegrouping() = verifyReplyNode(true, promptResident = true)

    private fun verifyReplyNode(carriesLogicalIds: Boolean, promptResident: Boolean = false) {
        val main = ManualMainDispatcher()
        Dispatchers.setMain(main)
        try {
            runDesktopComposeUiTest(width = 600, height = 1000) {
                val rig = openRig(promptResident)
                TimelineUiFrameRecorder(this, rig, main).use { recorder ->
                    try {
                        recorder.mount()
                        if (promptResident) recorder.advanceUntil("prompt resident", LIVE_FRAMES) {
                            recorder.frames.last().rows.any { it.key == "msg-prompt-otid" } && recorder.isQuiet
                        }
                        val before = streamReply(rig, recorder, promptResident)
                        val inkBefore = replyInk("before:$carriesLogicalIds:$promptResident", TEXT)
                        val key = recorder.frames.last().newestKey
                        val durable = listOf(
                            reply.copy(id = "stored-reply", runId = null, logicalMessageId = reply.id.takeIf { carriesLogicalIds }),
                            thought.copy(id = "stored-thought", runId = null, logicalMessageId = thought.id.takeIf { carriesLogicalIds }),
                            prompt,
                        )
                        runBlocking { rig.settle(durable) }
                        repeat(LIVE_FRAMES) {
                            recorder.advance("settle", 1)
                            val ink = replyInk("settle:$carriesLogicalIds:$promptResident", TEXT)
                            assertTrue(ink >= inkBefore * MINIMUM_INK_RATIO, "reply ink dipped at settlement: $ink / $inkBefore")
                        }
                        recorder.advanceUntil("settle", 1) { rig.drained }
                        recorder.assertReplyMountedOnce()
                        assertEquals(key, recorder.frames.last().newestKey, "the outer slot must survive")
                        assertEquals(before, onNodeWithText(TEXT, substring = true, useUnmergedTree = true).fetchSemanticsNode().id,
                            "canonical settlement disposed and recreated the rendered reply node")
                        val usage = UsageStatistics(id = "usage", promptTokens = 10, completionTokens = 3, date = DATE)
                        runBlocking { rig.settle(durable + usage) }
                        recorder.advance("later generation", LIVE_FRAMES)
                        replyInk("later:$carriesLogicalIds:$promptResident", TEXT)
                        recorder.writeFrameLog("settle-$carriesLogicalIds-$promptResident")
                        assertEquals(before, onNodeWithText(TEXT, substring = true, useUnmergedTree = true).fetchSemanticsNode().id)
                    } finally {
                        runBlocking { rig.close() }
                    }
                }
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun legacyReplyAndReasoningKeepTheirNodesAcrossCanonicalIds() = runDesktopComposeUiTest {
        val live = listOf(
            UiMessage("live-thought", "assistant", "Checking", DATE, runId = RUN,
                isReasoning = true, clientMessageId = "stream-thought"),
            UiMessage("live-reply", "assistant", TEXT, DATE, runId = RUN, clientMessageId = "stream-reply"),
        ).toImmutableList()
        var state by mutableStateOf(ChatUiState(messages = live, isLoadingMessages = false))
        val lifetimes = mutableListOf<Pair<String, Boolean>>()
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalRunStepLifetime provides { identity, mounted -> lifetimes += identity to mounted }) {
                    ChatTimeline(state, null, RecordingChatActions(), ChatSurfaceCapabilities.Default,
                        ChatSurfaceHost(), ChatSurfaceAppearance())
                }
            }
        }
        val before = onNodeWithText(TEXT, substring = true, useUnmergedTree = true).fetchSemanticsNode().id
        runOnIdle { state = state.copy(messages = live.map { it.copy(id = "stored-${it.id}") }.toImmutableList()) }
        waitForIdle()
        assertEquals(before, onNodeWithText(TEXT, substring = true, useUnmergedTree = true).fetchSemanticsNode().id)
        for (identity in listOf("stream-reply", "reasoning:stream-thought")) {
            assertEquals(listOf(identity to true), lifetimes.filter { it.first == identity })
        }
    }

    private fun openRig(promptResident: Boolean) = runBlocking {
        val rig = TimelineDomainRig.open(TimelineScope("backend", "conv-node", "agent"))
        if (promptResident) rig.openOn(listOf(prompt))
        rig
    }

    private fun ComposeUiTest.streamReply(
        rig: TimelineDomainRig,
        recorder: TimelineUiFrameRecorder,
        promptResident: Boolean,
    ): Int {
        val streamRun = RUN.takeUnless { promptResident }
        val stream = runBlocking { rig.beginStream() }
        runBlocking {
            stream.emit(prompt)
            stream.emit(thought.copy(runId = streamRun))
        }
        var firstNode: Int? = null
        for (text in listOf("The", "The final reply", TEXT)) {
            recorder.streaming(UiMessage(reply.id, "assistant", text, DATE, runId = streamRun), active = true)
            runBlocking { stream.emit(reply.copy(contentRaw = JsonPrimitive(text), runId = streamRun)) }
            recorder.advance("live token", LIVE_FRAMES)
            replyInk("live token", text)
            val node = onNodeWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNode().id
            if (firstNode == null) firstNode = node else assertEquals(firstNode, node)
        }
        runBlocking { stream.done() }
        recorder.streaming(UiMessage(reply.id, "assistant", TEXT, DATE, runId = streamRun), active = false)
        recorder.advance("live", LIVE_FRAMES)
        return checkNotNull(firstNode)
    }

    private fun TimelineUiFrameRecorder.assertReplyMountedOnce() {
        for (identity in listOf("stream-reply", "reasoning:stream-thought")) {
            assertEquals(listOf(identity to true), stepLifetimes.filter { it.first == identity },
                "the reply and reasoning must mount once and never dispose during settlement")
        }
    }

    private fun ComposeUiTest.replyInk(phase: String, text: String): Double {
        val interaction = onNodeWithText(text, substring = true, useUnmergedTree = true)
        val node = interaction.fetchSemanticsNode()
        val characters = node.config[SemanticsProperties.Text].sumOf { it.text.length }
        val ink = interaction.captureToImage().ink()
        println("settle-paint phase=$phase node=${node.id} characters=$characters ink=$ink")
        return ink
    }

    private fun ImageBitmap.ink(): Double {
        val pixels = toPixelMap()
        return (0 until height).sumOf { y -> (0 until width).sumOf { x -> 1.0 - pixels[x, y].luminance() } }
    }

    private companion object {
        const val MINIMUM_INK_RATIO = 0.98
        const val DATE = "2026-09-25T04:00:00Z"
        const val RUN = "live-run"
        const val TEXT = "The final reply stays on screen."
        const val LIVE_FRAMES = 40
    }
}
