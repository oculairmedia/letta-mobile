package com.letta.mobile.data.swarm

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.MessageId
import com.letta.mobile.data.swarm.ScriptedSwarmLink.Step
import com.letta.mobile.data.swarm.SwarmFakeHost.Companion.OTHER_CONVERSATION
import com.letta.mobile.data.swarm.SwarmFakeHost.Companion.PM_AGENT
import com.letta.mobile.data.swarm.SwarmFakeHost.Companion.PM_CONVERSATION
import com.letta.mobile.data.swarm.SwarmFakeHost.Companion.REPLY
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * letta-mobile-jvctq: swarm sends and reads through the REAL shared stack (AppServerSwarmSession ->
 * attachTurnStack -> AppServerTurnEngine) against a wire-shaped fake host: every staged receipt,
 * the queued disposition, idempotent retry and reconnect-resume, the approval-required terminal
 * (never answered), scope refusals and fail-closed link failures.
 */
class SwarmMessengerTest {
    @Test
    fun completedSendReportsEveryStageInOrderWithTheStoredReply() = runTest {
        val host = SwarmFakeHost(backgroundScope)
        val outbox = InMemorySwarmOutbox()

        val events = messenger(Step.Host(host), outbox = outbox).send(request(from = SenderLabel.of("orchestrator"))).toList()

        val receipts = events.filterIsInstance<DeliveryReceipt>()
        assertEquals(
            listOf(DeliveryStage.Connected, DeliveryStage.Accepted, DeliveryStage.Persisted, DeliveryStage.Processed),
            receipts.map { it.stage },
        )
        val connected = assertIs<DeliveryReceipt.Connected>(receipts[0])
        assertEquals(SwarmAgent(AgentId(PM_AGENT), "PM-letta-mobile"), connected.agent)
        assertEquals(InputDisposition.Started, assertIs<DeliveryReceipt.Accepted>(receipts[1]).disposition)
        val processed = assertIs<DeliveryReceipt.Processed>(receipts.last())
        assertEquals(TurnTerminal.Completed, processed.terminal)
        assertEquals(REPLY, processed.reply.text)

        val sent = host.userMessages.single().messages.single()
        assertEquals(ID.value, sent.clientMessageId, "the client message id is the input's idempotency key")
        assertTrue(sent.content.toString().contains("[from: orchestrator"), "the sender line leads the message")
        assertTrue(host.aborts.isEmpty(), "watching ends without aborting the host turn")
        assertEquals(OutboxStage.Delivered, outbox.entries.getValue(ID).stage)
    }

    @Test
    fun persistedIsFoundByTextWhenTheHostDoesNotEchoTheClientId() = runTest {
        val host = SwarmFakeHost(backgroundScope, echoOtid = false)

        val events = messenger(Step.Host(host)).send(request(waitFor = DeliveryStage.Persisted)).toList()

        assertIs<DeliveryReceipt.Persisted>(events.last())
    }

    @Test
    fun queuedInputIsAcceptedNotFailedAndCanReturnEarly() = runTest {
        val host = SwarmFakeHost(backgroundScope, ack = HostAck.Queued, turn = HostTurn.Silent)
        val outbox = InMemorySwarmOutbox()

        val events = messenger(Step.Host(host), outbox = outbox).send(request(waitFor = DeliveryStage.Accepted)).toList()

        val accepted = assertIs<DeliveryReceipt.Accepted>(events.last())
        assertEquals(InputDisposition.Queued, accepted.disposition)
        assertEquals(OutboxStage.Accepted, outbox.entries.getValue(ID).stage, "kept so status/resume can find it")
        assertTrue(host.aborts.isEmpty(), "returning early never aborts the queued input")
    }

    @Test
    fun rejectedInputIsAFailureValue() = runTest {
        val host = SwarmFakeHost(backgroundScope, ack = HostAck.Rejected)

        val events = messenger(Step.Host(host)).send(request()).toList()

        assertEquals(DeliveryFailure.Rejected("agent is archived"), events.last())
    }

    @Test
    fun approvalRequiredTerminalIsReportedAndNeverAnswered() = runTest {
        val host = SwarmFakeHost(backgroundScope, turn = HostTurn.NeedsApproval)

        val events = messenger(Step.Host(host)).send(request()).toList()

        val processed = assertIs<DeliveryReceipt.Processed>(events.last())
        val terminal = assertIs<TurnTerminal.NeedsApproval>(processed.terminal)
        assertEquals(listOf("Bash"), terminal.approvals.map { it.toolName })
        assertTrue(events.any { it is Activity.ApprovalRequested })
        assertTrue(host.approvalResponses.isEmpty(), "this client never answers an approval")
        assertTrue(host.aborts.isEmpty(), "the parked turn is left for the person")
    }

    @Test
    fun sameIdTwiceSendsOneMessageAndTheRetryResumesFromHistory() = runTest {
        val host = SwarmFakeHost(backgroundScope)
        val outbox = InMemorySwarmOutbox()
        val link = ScriptedSwarmLink(backgroundScope, Step.Host(host), Step.Host(host))
        val messenger = SwarmMessenger(link, outbox, POLICY, testScheduler.timeSource, nowMs = { testScheduler.currentTime })

        messenger.send(request()).toList()
        val retry = messenger.send(request()).toList()

        assertEquals(1, host.userMessages.size, "the same client message id is never sent twice")
        val resumed = assertIs<DeliveryReceipt.Processed>(retry.last())
        assertEquals(TurnTerminal.Unobserved, resumed.terminal)
        assertEquals(REPLY, resumed.reply.text)
    }

    @Test
    fun reusingAnIdForADifferentMessageIsRefused() = runTest {
        val host = SwarmFakeHost(backgroundScope)
        val outbox = InMemorySwarmOutbox()
        val link = ScriptedSwarmLink(backgroundScope, Step.Host(host))
        val messenger = SwarmMessenger(link, outbox, POLICY, testScheduler.timeSource, nowMs = { testScheduler.currentTime })

        messenger.send(request()).toList()
        val reuse = messenger.send(request(text = "something else")).toList()

        assertEquals(listOf<DeliveryEvent>(DeliveryFailure.IdReused(ID)), reuse)
    }

    @Test
    fun droppedLinkReconnectsAndResumesByMessageIdWithoutResending() = runTest {
        val host = SwarmFakeHost(backgroundScope, turn = HostTurn.DropLinkThenReply)

        val events = messenger(Step.Host(host), Step.Host(host)).send(request()).toList()

        assertEquals(1, host.userMessages.size, "resumed, not re-sent")
        val processed = assertIs<DeliveryReceipt.Processed>(events.last())
        assertEquals(TurnTerminal.Unobserved, processed.terminal, "the terminal was not observed: say so")
        assertEquals(REPLY, processed.reply.text)
        assertEquals(2, events.count { it is DeliveryReceipt.Connected })
    }

    @Test
    fun unreachableHostIsRetriedWithBackoffThenDelivered() = runTest {
        val host = SwarmFakeHost(backgroundScope)
        val unreachable = Step.Fail(LinkFailure.Unreachable("no route"))

        val events = messenger(unreachable, unreachable, Step.Host(host)).send(request()).toList()

        assertEquals(listOf(1, 2), events.filterIsInstance<Retrying>().map { it.attempt })
        assertIs<DeliveryReceipt.Processed>(events.last())
    }

    @Test
    fun deniedDeviceFailsClosedWithoutRetrying() = runTest {
        val link = ScriptedSwarmLink(backgroundScope, Step.Fail(LinkFailure.Denied("peer_not_paired")))
        val messenger = SwarmMessenger(link, InMemorySwarmOutbox(), POLICY, testScheduler.timeSource)

        val events = messenger.send(request()).toList()

        assertEquals(listOf<DeliveryEvent>(DeliveryFailure.Denied("peer_not_paired")), events)
        assertEquals(1, link.dials)
    }

    @Test
    fun conversationOfAnotherAgentIsRefusedBeforeAnythingIsSent() = runTest {
        val host = SwarmFakeHost(backgroundScope)

        val events = messenger(Step.Host(host)).send(request(target = ConversationTarget.Existing(ConversationId(OTHER_CONVERSATION)))).toList()

        assertIs<DeliveryFailure.ConversationOutOfScope>(events.single())
        assertTrue(host.inputs.isEmpty())
    }

    @Test
    fun agentOutsideTheDeviceScopeIsNotFound() = runTest {
        val host = SwarmFakeHost(backgroundScope).apply { agents.removeAll { it.first == PM_AGENT } }

        val events = messenger(Step.Host(host)).send(request()).toList()

        assertEquals(listOf<DeliveryEvent>(DeliveryFailure.AgentNotFound(AgentRef("pm-LETTA-mobile"))), events)
    }

    @Test
    fun newTargetCreatesOneConversationAndRecordsItBeforeSending() = runTest {
        val host = SwarmFakeHost(backgroundScope)
        val outbox = InMemorySwarmOutbox()

        val events = messenger(Step.Host(host), outbox = outbox).send(request(target = ConversationTarget.New)).toList()

        val connected = events.filterIsInstance<DeliveryReceipt.Connected>().single()
        assertEquals(PM_AGENT, host.conversations[connected.conversation.value])
        assertEquals(connected.conversation, outbox.entries.getValue(ID).conversation)
        assertEquals(1, host.adminCalls.count { it == "conversation.create" })
    }

    @Test
    fun readReturnsMessagesAfterSinceWithHostSenderMetadata() = runTest {
        val host = SwarmFakeHost(backgroundScope)
        val first = host.userMessage("hello", otid = null).also { host.store(PM_CONVERSATION, it) }
        host.store(PM_CONVERSATION, host.userMessage("from the phone", otid = null, sender = "device:phone"))

        val result = messenger(Step.Host(host)).read(AgentRef(PM_AGENT), ConversationId(PM_CONVERSATION), since = MessageId(first.getValue("id").jsonPrimitive.content))

        val messages = assertIs<SwarmResult.Ok<List<SwarmMessage>>>(result).value
        assertEquals(listOf("from the phone"), messages.map { it.text })
        assertEquals("device:phone", messages.single().senderId)
    }

    @Test
    fun readRefusesAConversationOutsideTheAgentsScope() = runTest {
        val host = SwarmFakeHost(backgroundScope)

        val result = messenger(Step.Host(host)).read(AgentRef(PM_AGENT), ConversationId(OTHER_CONVERSATION), since = null)

        assertIs<DeliveryFailure.ConversationOutOfScope>(assertIs<SwarmResult.Failed>(result).failure)
    }

    @Test
    fun ambiguousNamesListTheCandidates() {
        val agents = listOf(SwarmAgent(AgentId("a-1"), "PM"), SwarmAgent(AgentId("a-2"), "pm"))

        assertEquals(AgentResolution.Found(agents[0]), agents.resolveAgent(AgentRef("a-1")))
        assertEquals(AgentResolution.Ambiguous(agents), agents.resolveAgent(AgentRef("Pm")))
        assertEquals(AgentResolution.NotFound, agents.resolveAgent(AgentRef("nobody")))
    }

    private fun TestScope.messenger(vararg steps: Step, outbox: SwarmOutbox = InMemorySwarmOutbox()) =
        SwarmMessenger(
            ScriptedSwarmLink(backgroundScope, *steps),
            outbox,
            POLICY,
            testScheduler.timeSource,
            nowMs = { testScheduler.currentTime },
        )

    private fun request(
        target: ConversationTarget = ConversationTarget.Existing(ConversationId(PM_CONVERSATION)),
        waitFor: DeliveryStage = DeliveryStage.Processed,
        from: SenderLabel? = null,
        text: String = "Status of PR 2070?",
    ) = SwarmSendRequest(
        agent = AgentRef("pm-LETTA-mobile"),
        target = target,
        text = text,
        from = from,
        clientMessageId = ID,
        waitFor = waitFor,
        timeout = 5.minutes,
    )

    private companion object {
        val ID = ClientMessageId("swarm-test-1")
        val POLICY = DeliveryPolicy()
    }
}
