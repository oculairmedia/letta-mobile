package com.letta.mobile.data.voice.responder.chat

import com.letta.mobile.data.chat.sentruns.SentMessageId
import com.letta.mobile.data.chat.sentruns.SentMessageRuns
import com.letta.mobile.data.chat.sentruns.SentRunId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.responder.ClientMessageId
import com.letta.mobile.data.voice.responder.VoiceConversationKey
import com.letta.mobile.data.voice.responder.VoiceRowKind
import com.letta.mobile.data.voice.responder.VoiceRunId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ChatVoiceChatSessionTest {
    private val runs = MutableStateFlow(SentMessageRuns())
    private val page = FakeChatPage()
    private val sender = FakeIdentifiedSend(runs)
    private val conversation = MutableStateFlow<ConversationId?>(ConversationId("conv-1"))
    private val session = ChatVoiceChatSession(page, sender, conversation)

    @Test
    fun sendReturnsTheIdTheChatSessionEnqueuedTheMessageUnder() = runTest {
        assertEquals(ClientMessageId("c1"), session.send(VoiceText("hello")).id)
        assertEquals(ClientMessageId("c2"), session.send(VoiceText("again")).id)
        assertEquals(listOf("hello", "again"), sender.texts)
    }

    @Test
    fun aRefusedSendThrowsRatherThanInventingAnId() = runTest {
        sender.refuse = true
        assertFailsWith<ChatSendRefusedException> { session.send(VoiceText("hello")) }
    }

    @Test
    fun stopIsThePagesOwnStop() {
        session.stopRun()
        assertEquals(1, page.stops)
    }

    @Test
    fun theStatePublishesTheSentRunsAndConversationByIdentity() {
        runs.value = SentMessageRuns(
            started = mapOf(SentMessageId("c1") to setOf(SentRunId("iroh-run-1"), SentRunId("run-42"))),
            active = setOf(SentRunId("run-42")),
            failed = setOf(SentRunId("iroh-run-1")),
        )
        val state = session.state.value
        assertEquals(VoiceConversationKey("conv-1"), state.conversation)
        assertEquals(mapOf(ClientMessageId("c1") to setOf(VoiceRunId("iroh-run-1"), VoiceRunId("run-42"))), state.startedRuns)
        assertEquals(setOf(VoiceRunId("run-42")), state.activeRuns)
        assertEquals(setOf(VoiceRunId("iroh-run-1")), state.failedRuns)

        conversation.value = ConversationId("conv-2")
        assertEquals(VoiceConversationKey("conv-2"), session.state.value.conversation)
    }

    @Test
    fun rowsAreMappedByKindAndOnlyUserRowsCarryTheClientId() {
        page.show(
            userRow(RowId("local-1"), "hi", sentAs = SentMessageId("c1")),
            userRow(RowId("u-other"), "from elsewhere"),
            assistantRow(RowId("m1"), "Hello", run = RowRun("run-1")).copy(clientMessageId = "c1"),
            assistantRow(RowId("r1"), "thinking", run = RowRun("run-1")).copy(isReasoning = true),
            assistantRow(RowId("tc"), "", run = RowRun("run-1")).copy(
                toolCalls = listOf(UiToolCall(name = "search", arguments = "{}", result = null)),
            ),
            assistantRow(RowId("ap"), "", run = RowRun("run-1")).copy(
                approvalRequest = UiApprovalRequest(requestId = "q", toolCalls = emptyList()),
            ),
            assistantRow(RowId("e1"), "It broke", run = RowRun("run-1")).copy(role = "system", isError = true),
        )
        val rows = session.state.value.rows
        assertEquals(
            listOf(
                VoiceRowKind.USER, VoiceRowKind.USER, VoiceRowKind.ASSISTANT_TEXT, VoiceRowKind.OTHER,
                VoiceRowKind.OTHER, VoiceRowKind.OTHER, VoiceRowKind.ERROR,
            ),
            rows.map { it.kind },
        )
        assertEquals(listOf(ClientMessageId("c1"), null, null, null, null, null, null), rows.map { it.clientMessageId })
        assertEquals(VoiceRunId("run-1"), rows[2].runId)
        assertNull(rows[0].runId)
    }

    @Test
    fun rowKeysStayStableWhileTextStreamsTheUserRowConfirmsAndTheRunIsPromoted() {
        page.show(
            userRow(RowId("local-1"), "hi", sentAs = SentMessageId("c1")),
            assistantRow(RowId("m1"), "Hel", run = RowRun("iroh-run-1")),
        )
        val before = session.state.value.rows.map { it.key }

        page.show(
            userRow(RowId("server-9"), "hi", sentAs = SentMessageId("c1")),
            assistantRow(RowId("m1"), "Hello there", run = RowRun("run-42")),
        )
        val after = session.state.value.rows
        assertEquals(before, after.map { it.key })
        assertEquals(VoiceRunId("run-42"), after[1].runId)
    }

    @Test
    fun aSentRowAndAnAgentRowWithTheSameOtidKeepDistinctKeys() {
        page.show(
            userRow(RowId("local-1"), "hi", sentAs = SentMessageId("c1")),
            assistantRow(RowId("c1"), "Hello", run = RowRun("run-1")),
        )
        val keys = session.state.value.rows.map { it.key }
        assertEquals(keys.toSet().size, keys.size)
    }
}
