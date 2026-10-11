package com.letta.mobile.data.runtime

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.LettaCode0336Frames
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import com.letta.mobile.runtime.isAdvisory
import com.letta.mobile.runtime.isPresentationOnly
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * letta-mobile-bzvro.7 / .8 (F07, F08): the run-describing frames map to typed payloads, next to
 * (never instead of) what they mapped to before.
 */
class AppServerRuntimeEventMapperLiveStatusTest {
    private val mapper = AppServerRuntimeEventMapper()

    @Test
    fun everyLoopStatusMapsToItsPhase() {
        val expected = mapOf(
            "SENDING_API_REQUEST" to LoopPhase.SendingRequest,
            "WAITING_FOR_API_RESPONSE" to LoopPhase.WaitingForResponse,
            "RETRYING_API_REQUEST" to LoopPhase.Retrying,
            "PROCESSING_API_RESPONSE" to LoopPhase.ProcessingResponse,
            "EXECUTING_CLIENT_SIDE_TOOL" to LoopPhase.ExecutingClientTool,
            "EXECUTING_COMMAND" to LoopPhase.ExecutingCommand,
            "WAITING_ON_APPROVAL" to LoopPhase.WaitingOnApproval,
            "WAITING_ON_INPUT" to LoopPhase.WaitingOnInput,
            "SOMETHING_NEW_IN_0_40" to LoopPhase.Unknown,
        )
        expected.forEach { (status, phase) ->
            val payloads = map(loopStatus(status, activeRunIds = emptyList()))
            val changed = assertIs<RuntimeEventPayload.LoopPhaseChanged>(payloads.single(), status)
            assertEquals(phase, LoopPhase.fromWire(changed.status), status)
        }
    }

    @Test
    fun anActiveRunStillReportsRunningBeforeThePhase() {
        val payloads = map(LettaCode0336Frames.LOOP_STATUS_RETRYING)
        val running = assertIs<RuntimeEventPayload.RunLifecycleChanged>(payloads[0])
        assertEquals(RuntimeRunStatus.Running, running.status)
        assertEquals("RETRYING_API_REQUEST", assertIs<RuntimeEventPayload.LoopPhaseChanged>(payloads[1]).status)
    }

    @Test
    fun statusMapsToANoticeAtItsLevel() {
        val payloads = map(LettaCode0336Frames.STATUS_DELTA)
        assertEquals("status", assertIs<RuntimeEventPayload.RemoteStreamFrame>(payloads[0]).messageType)
        val notice = assertIs<RuntimeEventPayload.StatusNotice>(payloads[1])
        assertEquals("Compacting conversation", notice.message)
        assertEquals("warning", notice.level)
    }

    @Test
    fun slashCommandStartAndEndPairByCommandId() {
        val started = assertIs<RuntimeEventPayload.CommandStarted>(map(LettaCode0336Frames.SLASH_COMMAND_START)[1])
        assertEquals(RuntimeEventPayload.CommandStarted("cmd-1", "/compact", slash = true), started)
        val finished = assertIs<RuntimeEventPayload.CommandFinished>(map(LettaCode0336Frames.SLASH_COMMAND_END)[1])
        assertEquals("cmd-1", finished.commandId)
        assertEquals("Compacted 42 messages", finished.output)
        assertTrue(finished.success)
        assertTrue(finished.slash)
    }

    @Test
    fun commandEndKeepsItsPresentationFlags() {
        val finished = assertIs<RuntimeEventPayload.CommandFinished>(map(LettaCode0336Frames.COMMAND_END_DIM)[1])
        assertFalse(finished.success)
        assertTrue(finished.dimOutput)
        assertTrue(finished.preformatted)
        assertFalse(finished.slash)
    }

    @Test
    fun aCommandWithoutAnIdIsOnlyTheRawFrame() {
        val payloads = map(
            """{"type":"stream_delta","runtime":{"agent_id":"agent-1","conversation_id":"conv-1"},"event_seq":1,
               "emitted_at":"x","idempotency_key":"k","delta":{"message_type":"command_start","input":"ls"}}""",
        )
        assertIs<RuntimeEventPayload.RemoteStreamFrame>(payloads.single())
    }

    @Test
    fun malformedRetryFieldsDegradeToDefaults() {
        val payloads = map(
            """{"type":"stream_delta","runtime":{"agent_id":"agent-1","conversation_id":"conv-1"},"event_seq":1,
               "emitted_at":"x","idempotency_key":"k","delta":{"message_type":"retry","attempt":{"n":1},
               "max_attempts":"3","delay_ms":1500.0,"provider":["x"]}}""",
        )
        val retry = assertIs<RuntimeEventPayload.RetryNotice>(payloads[1])
        assertEquals(0, retry.attempt)
        assertEquals(3, retry.maxAttempts)
        assertEquals(1_500L, retry.delayMs)
        assertEquals(null, retry.provider)
    }

    @Test
    fun theLiveStatusPayloadsAreFlaggedForTurnBookkeeping() {
        assertTrue(RuntimeEventPayload.LoopPhaseChanged("X").isAdvisory)
        assertTrue(RuntimeEventPayload.RetryNotice().isAdvisory)
        assertTrue(RuntimeEventPayload.StatusNotice("x").isAdvisory)
        assertTrue(RuntimeEventPayload.ApprovalClassified().isAdvisory)
        assertFalse(RuntimeEventPayload.CommandStarted("c", "i").isAdvisory)
        assertTrue(RuntimeEventPayload.CommandStarted("c", "i").isPresentationOnly)
        assertFalse(RuntimeEventPayload.RunLifecycleChanged(RuntimeRunStatus.Running).isPresentationOnly)
    }

    @Test
    fun aCompactionEventStartsACompactionNextToItsRawFrame() {
        val payloads = map(LettaCode0336Frames.COMPACTION_EVENT)
        assertEquals("event_message", assertIs<RuntimeEventPayload.RemoteStreamFrame>(payloads[0]).messageType)
        assertEquals(RuntimeEventPayload.CompactionStarted(trigger = "context_window_overflow"), payloads[1])
    }

    @Test
    fun theSummaryMessageFinishesItWithItsStats() {
        val finished = assertIs<RuntimeEventPayload.CompactionFinished>(map(LettaCode0336Frames.COMPACTION_SUMMARY)[1])
        assertEquals("The user and agent set up the repo.", finished.summary)
        val stats = requireNotNull(finished.stats)
        assertEquals(150_000L, stats.contextTokensBefore)
        assertEquals(20_000L, stats.contextTokensAfter)
        assertEquals(200_000L, stats.contextWindow)
        assertEquals(48, stats.messagesCountBefore)
        assertEquals(12, stats.messagesCountAfter)
        assertEquals("context_window_overflow", stats.trigger)
    }

    @Test
    fun aSummaryWithoutStatsStillFinishes() {
        val payloads = map(
            """{"type":"stream_delta","runtime":{"agent_id":"agent-1","conversation_id":"conv-1"},"event_seq":1,
               "emitted_at":"x","idempotency_key":"k","delta":{"message_type":"summary_message","compaction_stats":"x"}}""",
        )
        assertEquals(RuntimeEventPayload.CompactionFinished(summary = "", stats = null), payloads[1])
    }

    @Test
    fun anotherEventKindIsOnlyTheRawFrame() {
        val payloads = map(
            """{"type":"stream_delta","runtime":{"agent_id":"agent-1","conversation_id":"conv-1"},"event_seq":1,
               "emitted_at":"x","idempotency_key":"k","delta":{"message_type":"event_message","event_type":"memory_update"}}""",
        )
        assertIs<RuntimeEventPayload.RemoteStreamFrame>(payloads.single())
    }

    @Test
    fun compactionPayloadsAreAdvisory() {
        assertTrue(RuntimeEventPayload.CompactionStarted().isAdvisory)
        assertTrue(RuntimeEventPayload.CompactionFinished().isPresentationOnly)
    }

    private fun map(json: String): List<RuntimeEventPayload> =
        mapper.map(command, AppServerProtocol.decodeFrame(json.trimIndent())).map { it.payload }

    private fun loopStatus(status: String, activeRunIds: List<String>): String =
        """{"type":"update_loop_status","runtime":{"agent_id":"agent-1","conversation_id":"conv-1"},"event_seq":1,
           "emitted_at":"x","idempotency_key":"k","loop_status":{"status":"$status",
           "active_run_ids":[${activeRunIds.joinToString(",") { "\"$it\"" }}]}}"""

    private val command = TurnCommand(
        backendId = BackendId("backend-1"),
        runtimeId = RuntimeId("runtime-1"),
        agentId = AgentId("agent-1"),
        conversationId = ConversationId("conv-1"),
        input = TurnInput.UserMessage(localMessageId = "local-1", text = "hello"),
    )
}
