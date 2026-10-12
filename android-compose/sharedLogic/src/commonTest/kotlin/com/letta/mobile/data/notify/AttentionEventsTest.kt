package com.letta.mobile.data.notify

import com.letta.mobile.data.chat.runtime.MapSettingsStore
import com.letta.mobile.data.presence.ConversationRunPhasePublisher
import com.letta.mobile.data.presence.ConversationRunRegistry
import com.letta.mobile.data.presence.ConversationRunState
import com.letta.mobile.data.presence.RunScope
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.ToolApprovalDecision
import com.letta.mobile.runtime.ToolApprovalDecisionValue
import com.letta.mobile.runtime.ToolApprovalId
import com.letta.mobile.runtime.ToolApprovalRequest
import com.letta.mobile.runtime.ToolApprovalScope
import com.letta.mobile.runtime.ToolCallId
import com.letta.mobile.runtime.ToolName
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-bzvro.27 (F27): approval notifications come from the run registry the reducer feeds,
 * post once per request id while the person is away, and never for a muted agent.
 */
class AttentionEventsTest {
    private var clock = 1_000L
    private val registry = ConversationRunRegistry()
    private val publisher = ConversationRunPhasePublisher(registry) { clock }
    private val scope = RunScope(conversationId = "c1", agentId = "a1")

    private fun feed(event: RuntimeEventPayload, on: RunScope = scope): Map<String, ConversationRunState> {
        clock += 10
        publisher.onEvent(on, event)
        return registry.runs.value
    }

    private fun approval(id: String, callId: String = "t-$id", tool: String = "Bash") =
        RuntimeEventPayload.ApprovalRequested(
            ToolApprovalRequest(ToolApprovalId(id), ToolCallId(callId), ToolName(tool), prompt = "Allow $tool?"),
        )

    private fun approved(id: String, callId: String = "t-$id") = RuntimeEventPayload.ApprovalResolved(
        ToolApprovalDecision(ToolApprovalId(id), ToolCallId(callId), ToolApprovalDecisionValue.Approved, ToolApprovalScope.Once),
    )

    @Test
    fun aParkedApprovalIsRaisedOnceWithItsRequestId() {
        val before = feed(RuntimeEventPayload.LocalUserAppend("l1", "go"))
        val parked = feed(approval("ap-1", tool = "Bash"))

        val raised = AttentionEvents.approvalsRaised(before, parked)

        assertEquals(listOf(AttentionEvent.ApprovalNeeded("a1", "c1", "ap-1", "Bash")), raised)
        // The same request replayed by a reconnect changes nothing the diff can see.
        assertTrue(AttentionEvents.approvalsRaised(parked, feed(approval("ap-1"))).isEmpty())
    }

    @Test
    fun aSecondRequestInTheSameTurnIsRaisedAndAResolvedOneClearsTheId() {
        feed(RuntimeEventPayload.LocalUserAppend("l1", "go"))
        val first = feed(approval("ap-1"))
        val second = feed(approval("ap-2", tool = "Write"))
        assertEquals(listOf("ap-2"), AttentionEvents.approvalsRaised(first, second).map { it.requestId })

        feed(approved("ap-2"))
        assertNull(registry.runs.value.getValue("c1").awaitingApprovalId)
    }

    @Test
    fun aTurnThatEndsDropsTheParkedId() {
        feed(approval("ap-1"))
        val done = feed(RuntimeEventPayload.RunLifecycleChanged(RuntimeRunStatus.Completed))
        assertNull(done.getValue("c1").awaitingApprovalId)
        assertFalse(done.getValue("c1").awaitingApproval)
    }

    @Test
    fun theGatePostsOnlyWhileTheConversationIsUnattended() {
        var attending = true
        val gate = AttentionGate(prefs = { AttentionPrefs() }, focus = { attending })
        assertEquals(
            AttentionDecision.Suppress(AttentionSuppression.Attended),
            gate.decide(AttentionEvent.ApprovalNeeded("a1", "c1", "ap-1", "Bash")),
        )
        attending = false
        assertIs<AttentionDecision.Post>(gate.decide(AttentionEvent.ApprovalNeeded("a1", "c1", "ap-2", "Bash")))
    }

    @Test
    fun aRequestIdIsDecidedOnceEvenAfterAResync() {
        val gate = AttentionGate(prefs = { AttentionPrefs() }, focus = { false })
        val event = AttentionEvent.ApprovalNeeded("a1", "c1", "ap-1", "Bash")
        assertIs<AttentionDecision.Post>(gate.decide(event))
        // A resync rebuilt the run from rest and raised the same request again.
        assertEquals(AttentionDecision.Suppress(AttentionSuppression.Duplicate), gate.decide(event.copy(toolName = null)))
    }

    @Test
    fun anApprovalFirstSeenWhileLookingNeverPostsLater() {
        var attending = true
        val gate = AttentionGate(prefs = { AttentionPrefs() }, focus = { attending })
        val event = AttentionEvent.ApprovalNeeded("a1", "c1", "ap-1", "Bash")
        gate.decide(event)
        attending = false
        assertEquals(AttentionDecision.Suppress(AttentionSuppression.Duplicate), gate.decide(event))
    }

    @Test
    fun aMutedAgentPostsNothingAndTheSwitchSilencesApprovalsOnly() {
        var prefs = AttentionPrefs(mutedAgentIds = setOf("a1"))
        val gate = AttentionGate(prefs = { prefs }, focus = { false })
        assertEquals(
            AttentionDecision.Suppress(AttentionSuppression.Muted),
            gate.decide(AttentionEvent.ApprovalNeeded("a1", "c1", "ap-1", null)),
        )
        assertEquals(
            AttentionDecision.Suppress(AttentionSuppression.Muted),
            gate.decide(AttentionEvent.TurnDone("a1", "c1", "1")),
        )
        assertIs<AttentionDecision.Post>(gate.decide(AttentionEvent.ApprovalNeeded("a2", "c2", "ap-2", null)))

        prefs = AttentionPrefs(approvalNotificationsEnabled = false)
        assertEquals(
            AttentionDecision.Suppress(AttentionSuppression.ApprovalsDisabled),
            gate.decide(AttentionEvent.ApprovalNeeded("a2", "c2", "ap-3", null)),
        )
        assertIs<AttentionDecision.Post>(gate.decide(AttentionEvent.TurnDone("a2", "c2", "2")))
    }

    @Test
    fun theRememberedKeysAreBounded() {
        val gate = AttentionGate(prefs = { AttentionPrefs() }, focus = { false }, capacity = 2)
        val first = AttentionEvent.ApprovalNeeded("a1", "c1", "ap-1", null)
        gate.decide(first)
        gate.decide(first.copy(requestId = "ap-2"))
        gate.decide(first.copy(requestId = "ap-3"))
        // ap-1 was the oldest and was dropped, so it is new again.
        assertIs<AttentionDecision.Post>(gate.decide(first))
    }

    @Test
    fun theMonitorPostsExactlyOneNotificationPerUnfocusedRequest() = runTest {
        feed(RuntimeEventPayload.LocalUserAppend("l1", "go"))
        val parked = feed(approval("ap-1"))
        val replayed = feed(approval("ap-1"))
        val mutedAgent = feed(approval("ap-9"), on = RunScope("c2", "muted"))
        val gate = AttentionGate(prefs = { AttentionPrefs(mutedAgentIds = setOf("muted")) }, focus = { false })
        val posted = mutableListOf<AttentionEvent.ApprovalNeeded>()

        ApprovalAttentionMonitor(gate).watch(flowOf(parked, emptyMap(), replayed, mutedAgent)) { posted += it }

        assertEquals(listOf("ap-1"), posted.map { it.requestId })
    }

    @Test
    fun mutesAndTheApprovalSwitchSurviveARestart() {
        val store = MapSettingsStore()
        StoredAttentionPrefs(store).apply {
            setAgentMuted("a1", true)
            setAgentMuted("a2", true)
            setAgentMuted("a2", false)
            setApprovalNotificationsEnabled(false)
        }
        val reloaded = StoredAttentionPrefs(store).prefs.value
        assertEquals(setOf("a1"), reloaded.mutedAgentIds)
        assertFalse(reloaded.approvalNotificationsEnabled)

        StoredAttentionPrefs(store).apply {
            setAgentMuted("a1", false)
            setApprovalNotificationsEnabled(true)
        }
        assertEquals(AttentionPrefs(), StoredAttentionPrefs(store).prefs.value)
        assertNull(store.getString("${StoredAttentionPrefs.DEFAULT_KEY_PREFIX}.mutedAgents"))
    }

    @Test
    fun theCopyNamesTheAgentAndTheTool() {
        assertEquals("Scout needs your go-ahead", AttentionCopy.approvalTitle("Scout"))
        assertEquals("Your agent needs your go-ahead", AttentionCopy.approvalTitle(" "))
        assertEquals(
            "Bash is paused until you allow or deny it.",
            AttentionCopy.approvalBody(AttentionEvent.ApprovalNeeded("a1", "c1", "ap-1", "Bash")),
        )
    }
}
