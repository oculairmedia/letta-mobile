package com.letta.mobile.data.voice.responder.chat

import com.letta.mobile.data.chat.send.ChatSendCoordinator
import com.letta.mobile.data.chat.send.ChatSendCoordinatorConversationFixture
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.transport.BridgeTurnStatus
import com.letta.mobile.data.transport.WsTimelineEvent
import com.letta.mobile.data.voice.VoiceLoopConfig
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.activation.VoiceActivationKind
import com.letta.mobile.data.voice.loop.EngineLog
import com.letta.mobile.data.voice.loop.FakeEar
import com.letta.mobile.data.voice.loop.FakeMouth
import com.letta.mobile.data.voice.loop.VoiceEngines
import com.letta.mobile.data.voice.loop.VoiceLoop
import com.letta.mobile.data.voice.loop.VoiceLoopRuntime
import com.letta.mobile.data.voice.responder.VoiceChatResponder
import com.letta.mobile.data.voice.session.telemetry.TelemetryResponder
import com.letta.mobile.data.voice.session.telemetry.TelemetryVoiceChatSession
import com.letta.mobile.data.voice.session.telemetry.VoiceTelemetry
import com.letta.mobile.ui.chat.session.VoiceModeTelemetry
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.TelemetryRecorder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The voice loop (real machine, fake ear and mouth) over the real voice responder, the chat
 * adapter and the real send coordinator, fed the two shapes of the third live test on the
 * desktop's canonical route, where the reply streams in the presentation's live overlay and the
 * page's own message list never holds it:
 *
 * - not queued: the provisional run is attributed from the echo, the durable run promotes it,
 *   the tool step runs on `local-run-543` and the reply text on `local-run-544`, then TurnDone;
 * - queued: the turn starts after the send was accepted on the durable `local-run-537`, with no
 *   echo, the reply text on `local-run-538`, then TurnDone.
 *
 * Every row is stamped with the send's otid as its turn, as the host stamps them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ChatVoiceLiveOverlayLoopTest : ChatSendCoordinatorConversationFixture() {
    private val timeline = RecordingTimelineWriter()
    private val recorder = TelemetryRecorder()
    private var installed: AutoCloseable? = null

    // The bridge deduplicates message frames process-wide, so this test's frames need their own ids.
    private val prefix = "voice-overlay-${Random.nextLong()}"

    @BeforeTest
    fun install() {
        installed = Telemetry.installRecorder(recorder)
    }

    @AfterTest
    fun uninstall() {
        installed?.close()
    }

    private class Rig(
        val coordinator: ChatSendCoordinator,
        val live: MutableStateFlow<List<UiMessage>>,
        val mouth: FakeMouth,
        val ear: FakeEar,
    )

    private fun TestScope.rig(): Rig {
        val transport = FakeChannelTransport(mutableListOf(true), activeChatTurn = true)
        val coordinator = coordinator(timeline, RecordingUiSink(), transport) { CONV }
        val live = MutableStateFlow<List<UiMessage>>(emptyList())
        val chat = ChatVoiceChatSession(FakeChatPage(), coordinator.identifiedSend, MutableStateFlow(ConversationId(CONV)), live)
        val telemetry = VoiceTelemetry(sink = VoiceModeTelemetry)
        val session = TelemetryVoiceChatSession(chat, telemetry)
        val log = EngineLog()
        val ear = FakeEar(log)
        val mouth = FakeMouth(log)
        val engines = VoiceEngines(ear, TelemetryResponder(VoiceChatResponder(session), telemetry, session), mouth)
        val loop = VoiceLoop(engines, VoiceLoopConfig(), VoiceLoopRuntime(backgroundScope, UnconfinedTestDispatcher(testScheduler)))
        loop.activate(VoiceActivationKind.TAP)
        advanceUntilIdle()
        return Rig(coordinator, live, mouth, ear)
    }

    private suspend fun TestScope.ask(rig: Rig): String {
        rig.ear.say(VoiceText("what time is it"))
        advanceUntilIdle()
        return timeline.externalLocals.single().otid
    }

    private fun row(message: LettaMessage, turn: String) =
        WsTimelineEvent.MessageDelta(message = message, conversationId = CONV, turnId = turn)

    /** A frame's run and the turn (the send's otid) it is stamped with. */
    private data class Stamp(val run: String, val turn: String)

    /** The runs the live overlay draws: the reply's, and the tool step's before it, if any. */
    private data class OverlayRuns(val reply: String, val tool: String? = null)

    private fun reply(text: String, stamp: Stamp) = row(
        AssistantMessage(id = "$prefix-reply", contentRaw = JsonPrimitive(text), date = DATE, runId = stamp.run, turnId = stamp.turn),
        stamp.turn,
    )

    private fun toolStep(stamp: Stamp) =
        row(ToolCallMessage(id = "$prefix-tool", date = DATE, runId = stamp.run, turnId = stamp.turn), stamp.turn)

    /** The send's echo on [stamp]'s run; its turn is the send's otid. */
    private fun echo(stamp: Stamp) = row(
        UserMessage(id = "$prefix-echo", contentRaw = JsonPrimitive("hi"), date = DATE, otid = stamp.turn, runId = stamp.run, turnId = stamp.turn),
        stamp.turn,
    )

    /** The live overlay shows the reply row (and the tool step before it), as the canonical route draws it. */
    private fun Rig.overlay(text: String, runs: OverlayRuns) {
        val tool = runs.tool?.let { UiMessage(id = "$prefix-tool", role = "assistant", content = "", timestamp = "t", runId = it, isReasoning = true) }
        live.update { listOfNotNull(tool, assistantRow(RowId("$prefix-reply"), text, RowRun(runs.reply))) }
    }

    private fun completedCharacters(): Int = recorder.events.value
        .single { it.tag == "VoiceMode" && it.name == "reply.completed" }
        .attrs.getValue("characters").toString().toInt()

    @Test
    fun anUnqueuedReplyOnAPromotedThenRotatedRunIsSpokenFromTheLiveOverlay() = runTest(UnconfinedTestDispatcher()) {
        val rig = rig()
        val otid = ask(rig)

        rig.coordinator.handleEvent(WsTimelineEvent.TurnStarted(TURN, AGENT, CONV, "iroh-run-8b6e"))
        rig.coordinator.handleEvent(echo(Stamp("iroh-run-8b6e", otid)))
        rig.coordinator.handleEvent(WsTimelineEvent.TurnStarted(TURN, AGENT, CONV, "local-run-543"))
        rig.coordinator.handleEvent(toolStep(Stamp("local-run-543", otid)))
        rig.overlay("", OverlayRuns("local-run-544", tool = "local-run-543"))
        advanceUntilIdle()
        rig.coordinator.handleEvent(reply("It is noon. It is sunny.", Stamp("local-run-544", otid)))
        rig.overlay("It is noon. It is sunny.", OverlayRuns("local-run-544", tool = "local-run-543"))
        advanceUntilIdle()
        rig.coordinator.handleEvent(WsTimelineEvent.TurnDone(TURN, "local-run-543", BridgeTurnStatus.Completed))
        advanceUntilIdle()

        assertEquals(listOf("It is noon.", "It is sunny."), rig.mouth.spokenTexts.map { it.trim() })
        assertTrue(completedCharacters() > 0)
    }

    @Test
    fun aQueuedReplyThatStartsOnTheDurableRunIsSpokenFromTheLiveOverlay() = runTest(UnconfinedTestDispatcher()) {
        val rig = rig()
        val otid = ask(rig)

        rig.coordinator.handleEvent(WsTimelineEvent.TurnStarted(TURN, AGENT, CONV, "local-run-537", dequeued = true))
        rig.coordinator.handleEvent(toolStep(Stamp("local-run-537", otid)))
        advanceUntilIdle()
        rig.coordinator.handleEvent(reply("Noon.", Stamp("local-run-538", otid)))
        rig.overlay("Noon.", OverlayRuns("local-run-538", tool = "local-run-537"))
        advanceUntilIdle()
        rig.coordinator.handleEvent(WsTimelineEvent.TurnDone(TURN, "local-run-537", BridgeTurnStatus.Completed))
        advanceUntilIdle()

        assertEquals(listOf("Noon."), rig.mouth.spokenTexts.map { it.trim() })
        assertTrue(completedCharacters() > 0)
    }

    private companion object {
        const val AGENT = "agent-1"
        const val CONV = "conv-1"
        const val TURN = "iroh-turn-ee681969"
        const val DATE = "2026-11-12T10:00:00Z"
    }
}
