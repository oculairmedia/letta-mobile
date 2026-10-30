package com.letta.mobile.data.chat.send

import com.letta.mobile.data.runtime.INPUT_QUEUED_REASON
import com.letta.mobile.data.transport.IngestFrameDeduplicator
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.TransportFrameEvent
import com.letta.mobile.data.transport.iroh.RuntimeEventServerFrameMapper
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeRunStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
internal class ChatSendCoordinatorServerQueueTest : ChatSendCoordinatorServerQueueFixture() {
    @Test
    fun dequeuedStartClearsBannerBeforeReasoning() = runTest(UnconfinedTestDispatcher()) {
        assertStartedInputClearsBanner(Frame.Reasoning)
    }

    @Test
    fun dequeuedStartClearsBannerBeforeAssistantText() = runTest(UnconfinedTestDispatcher()) {
        assertStartedInputClearsBanner(Frame.Assistant)
    }

    @Test
    fun canonicalReasoningClearsBannerWithOlderHost() = runTest(UnconfinedTestDispatcher()) {
        assertFirstFrameClearsBanner(Frame.Reasoning)
    }

    @Test
    fun canonicalAssistantTextClearsBannerWithOlderHost() = runTest(UnconfinedTestDispatcher()) {
        assertFirstFrameClearsBanner(Frame.Assistant)
    }

    @Test
    fun startedInputWithNoAssistantTextClearsBannerAtTerminal() = runTest(UnconfinedTestDispatcher()) {
        val chat = queuedInput()
        chat.lifecycle(RuntimeRunStatus.Started, canonicalContext)
        chat.lifecycle(RuntimeRunStatus.Completed, canonicalContext)
        assertBannerCleared(chat)
    }

    @Test
    fun canonicalTerminalClearsBannerWithoutStartOrText() = runTest(UnconfinedTestDispatcher()) {
        val chat = queuedInput()
        chat.lifecycle(RuntimeRunStatus.Completed, canonicalContext)
        assertBannerCleared(chat)
    }

    @Test
    fun queuedBehindLocallySubmittedRunningTurnDoesNotSayOtherClient() = runTest(UnconfinedTestDispatcher()) {
        val timeline = RecordingTimelineWriter()
        val transport = FakeChannelTransport(mutableListOf(true, true), activeChatTurn = true)
        val chat = coordinator(timeline, RecordingUiSink(), transport, backgroundScope) { CONVERSATION }
        chat.send("previous response").join()
        chat.deliver(startFrame(TurnRun("previous-turn", "local-run-368")))
        // The transport accepts the next input while the previous server run is still finishing.
        chat.send("it's gone").join()
        chat.deliver(startFrame(TurnRun(LOCAL_TURN, "iroh-run-placeholder")))
        chat.lifecycle(RuntimeRunStatus.Running, localContext, INPUT_QUEUED_REASON)

        assertEquals(listOf("previous response", "it's gone"), transport.sentTexts)
        assertEquals("it's gone", queue(chat)?.queuedOnServer?.text)
        assertNotEquals(ServerQueueBlocker.OtherClient, queue(chat)?.queuedOnServerBehind)
    }

    @Test
    fun locallyQueuedSendDrainedAfterOwnTerminalKeepsOwnBlocker() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeChannelTransport(mutableListOf(true, false, true), activeChatTurn = true)
        val chat = coordinator(RecordingTimelineWriter(), RecordingUiSink(), transport, backgroundScope) { CONVERSATION }
        chat.send("previous response").join()
        chat.deliver(startFrame(TurnRun("previous-turn", "local-run-368")))
        chat.send("it's gone").join()
        assertEquals("it's gone", queue(chat)?.items?.single()?.text)
        transport.activeChatTurn = false
        chat.lifecycle(RuntimeRunStatus.Completed, localContext.copy(turnId = "previous-turn", runId = "local-run-368"))
        chat.deliver(startFrame(TurnRun(LOCAL_TURN, "iroh-run-placeholder")))
        chat.lifecycle(RuntimeRunStatus.Running, localContext, INPUT_QUEUED_REASON)

        assertEquals(listOf("previous response", "it's gone", "it's gone"), transport.sentTexts)
        assertEquals("it's gone", queue(chat)?.queuedOnServer?.text)
        assertNotEquals(ServerQueueBlocker.OtherClient, queue(chat)?.queuedOnServerBehind)
    }

    @Test
    fun queuedBehindObservedOtherClientStillSaysOtherClient() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeChannelTransport(mutableListOf(true, true), activeChatTurn = true)
        val chat = coordinator(RecordingTimelineWriter(), RecordingUiSink(), transport, backgroundScope) { CONVERSATION }
        chat.send("earlier local response").join()
        chat.deliver(startFrame(TurnRun("earlier-local-turn", "local-run-367")))
        chat.lifecycle(RuntimeRunStatus.Completed, localContext.copy(turnId = "earlier-local-turn", runId = "local-run-367"))
        chat.deliver(startFrame(TurnRun("other-device-turn", "local-run-368")))
        chat.send("it's gone").join()
        chat.deliver(startFrame(TurnRun(LOCAL_TURN, "iroh-run-placeholder")))
        chat.lifecycle(RuntimeRunStatus.Running, localContext, INPUT_QUEUED_REASON)

        assertEquals(ServerQueueBlocker.OtherClient, queue(chat)?.queuedOnServerBehind)
    }

    @Test
    fun replayAndPreviousTurnFramesDoNotClearTheQueuedInput() = runTest(UnconfinedTestDispatcher()) {
        val chat = queuedInput()
        chat.deliver(textFrame(Frame.User, CLIENT_MESSAGE))
        chat.deliver(textFrame(Frame.Reasoning, CLIENT_MESSAGE), replay = true)
        chat.deliver(textFrame(Frame.Assistant, "previous-turn"))
        chat.lifecycle(RuntimeRunStatus.Completed, localContext.copy(turnId = "previous-turn", runId = "local-run-368"))
        assertEquals("it's gone", queue(chat)?.queuedOnServer?.text)
    }

}

@OptIn(ExperimentalCoroutinesApi::class)
internal abstract class ChatSendCoordinatorServerQueueFixture : ChatSendCoordinatorConversationFixture() {
    private val ingest = IngestFrameDeduplicator()
    private val framePrefix = "queued-banner-${Random.nextLong()}"

    protected suspend fun TestScope.assertStartedInputClearsBanner(type: Frame) {
        val chat = queuedInput()
        // update_queue itself is a passthrough event; the engine emits Started on its dequeued transition.
        chat.lifecycle(RuntimeRunStatus.Started, localContext)
        assertBannerCleared(chat)
        chat.deliver(textFrame(type, CLIENT_MESSAGE))
        assertBannerCleared(chat)
        chat.lifecycle(RuntimeRunStatus.Running, localContext, INPUT_QUEUED_REASON)
        assertBannerCleared(chat)
    }

    protected suspend fun TestScope.assertFirstFrameClearsBanner(type: Frame) {
        val chat = queuedInput()
        chat.deliver(textFrame(type, CLIENT_MESSAGE))
        assertBannerCleared(chat)
    }

    protected suspend fun TestScope.queuedInput(): ChatSendCoordinator {
        val timeline = RecordingTimelineWriter()
        val transport = FakeChannelTransport(mutableListOf(true), activeChatTurn = true)
        val chat = coordinator(timeline, RecordingUiSink(), transport, backgroundScope) { CONVERSATION }
        chat.send("it's gone").join()
        // Iroh announces its local identity before input acceptance; the host stamps text with the OTID.
        chat.deliver(startFrame(TurnRun(LOCAL_TURN, "iroh-run-placeholder")))
        chat.lifecycle(RuntimeRunStatus.Running, localContext, INPUT_QUEUED_REASON)
        chat.lifecycle(RuntimeRunStatus.Completed, localContext.copy(turnId = "previous-turn", runId = "local-run-368"))
        assertEquals("it's gone", queue(chat)?.queuedOnServer?.text)
        assertEquals("it's gone", timeline.externalLocals.single().content)
        assertTrue(queue(chat)?.items.orEmpty().isEmpty(), "the input is already on the server")
        return chat
    }

    protected suspend fun ChatSendCoordinator.lifecycle(
        status: RuntimeRunStatus,
        context: RuntimeEventServerFrameMapper.Context,
        reason: String? = null,
    ) {
        val lifecycleReason = reason ?: "Queued input started".takeIf { status == RuntimeRunStatus.Started }
        RuntimeEventServerFrameMapper.map(RuntimeEventPayload.RunLifecycleChanged(status, lifecycleReason), context)
            .forEach { deliver(it) }
    }

    protected suspend fun ChatSendCoordinator.deliver(frame: ServerFrame, replay: Boolean = false) {
        val event = TransportFrameEvent(frame, replay)
        if (!ingest.isDuplicate(event)) event.timelineEvent?.let { handleEvent(it) }
    }

    protected fun textFrame(frame: Frame, turn: String): ServerFrame = RuntimeEventServerFrameMapper.map(
        RuntimeEventPayload.RemoteStreamFrame(
            frameId = "frame-${frame.wire}-$turn",
            messageType = frame.wire,
            body = buildJsonObject {
                put("message_type", frame.wire)
                put("turn_id", turn)
                put("logical_message_id", "$framePrefix-${frame.wire}-$turn")
                put("text_seq", 1)
                put("content", "reply")
                put("reasoning", "thinking")
                put("run_id", "local-run-369")
            }.toString(),
        ),
        localContext,
    ).single()

    protected fun startFrame(turn: TurnRun) = ServerFrame.TurnStarted(
        id = "start-${turn.turnId}-${turn.runId}", ts = "2026-10-30T18:55:00Z", agentId = "agent-1",
        conversationId = CONVERSATION, turnId = turn.turnId, runId = turn.runId,
    )

    /** A turn and the run it executes under, as the host stamps them on lifecycle frames. */
    protected data class TurnRun(val turnId: String, val runId: String)

    /** The stream message kinds these tests deliver, with their wire names. */
    protected enum class Frame(val wire: String) { User("user_message"), Reasoning("reasoning_message"), Assistant("assistant_message") }

    protected fun queue(chat: ChatSendCoordinator) = chat.sendQueue.state.value[QueueConversationId(CONVERSATION)]

    protected fun assertBannerCleared(chat: ChatSendCoordinator) =
        assertNull(queue(chat)?.queuedOnServer, "a started input must disappear from the composer queue banner")

    protected val localContext = RuntimeEventServerFrameMapper.Context("agent-1", CONVERSATION, LOCAL_TURN, "iroh-run-placeholder")
    protected val canonicalContext get() = localContext.copy(turnId = CLIENT_MESSAGE, runId = "local-run-369")

    companion object {
        const val CONVERSATION = "conv-1"
        const val LOCAL_TURN = "iroh-turn-local"
        // The shared coordinator fixture generates this first clientMessageId/OTID.
        const val CLIENT_MESSAGE = "otid-1"
    }
}
