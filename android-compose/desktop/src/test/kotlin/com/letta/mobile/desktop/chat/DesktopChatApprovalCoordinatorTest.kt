package com.letta.mobile.desktop.chat

import com.letta.mobile.data.runtime.PendingApprovalDetails
import com.letta.mobile.data.runtime.binding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** A request with parallel calls is answered one gate at a time. */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopChatApprovalCoordinatorTest {
    private val conversation = DesktopConversationSummary(
        id = "conv-1", title = "t", agentName = "a", updatedAtLabel = "", lastMessagePreview = "", agentId = "agent-1",
    )

    @Test
    fun theCardForTheNextParallelCallIsActionableOnceTheAnsweredOneResolves() = runTest {
        val parked = MutableStateFlow(mapOf("A" to details("A"), "B" to details("B")))
        val gateway = RecordingGateway(parked)
        val coordinator = bound(gateway)

        coordinator.submitApproval(request(gateway, answering = "A"))
        runCurrent()
        assertEquals(setOf("req"), coordinator.submittingApprovals.value, "A's answer is in flight")
        assertEquals("A", gateway.submissions.single().toolCallId, "the plain decision targets the call the card drew")

        parked.value = mapOf("B" to details("B")) // A resolved; the card redraws for B
        runCurrent()

        assertTrue(coordinator.submittingApprovals.value.isEmpty(), "B's buttons must not stay disabled until the whole request is decided")
    }

    @Test
    fun aSingleCallStaysInFlightUntilTheTimelineShowsItDecided() = runTest {
        val parked = MutableStateFlow(mapOf("A" to details("A")))
        val gateway = RecordingGateway(parked)
        val coordinator = bound(gateway)

        coordinator.submitApproval(request(gateway, answering = "A", calls = listOf("A")))
        runCurrent()
        parked.value = emptyMap()
        runCurrent()

        assertEquals(setOf("req"), coordinator.submittingApprovals.value, "no sibling took over: still waiting on the timeline")
    }

    private fun TestScope.bound(gateway: RecordingGateway) =
        DesktopChatApprovalCoordinator(backgroundScope) {}.also {
            it.bindGateway(gateway)
            runCurrent()
        }

    private fun request(gateway: RecordingGateway, answering: String, calls: List<String> = listOf("A", "B")) =
        ApprovalSubmissionRequest(
            gateway = gateway,
            conversation = conversation,
            requestId = "req",
            toolCallIds = calls,
            approve = true,
            reason = null,
            suggestionBinding = details(answering).binding,
        )

    private fun details(call: String) = PendingApprovalDetails(approvalId = "perm-$call", toolCallId = call, toolName = "Bash")

    private class RecordingGateway(
        override val pendingApprovalDetails: MutableStateFlow<Map<String, PendingApprovalDetails>>,
    ) : FakeDesktopChatGateway(), DesktopApprovalSubmitter, DesktopPendingApprovalSource {
        val submissions = mutableListOf<DesktopApprovalSubmission>()

        override suspend fun submitApproval(submission: DesktopApprovalSubmission) {
            submissions += submission
        }
    }
}
