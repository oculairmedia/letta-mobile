package com.letta.mobile.data.a2ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** letta-mobile-by4vm.2: an A2UI approval answer is admitted only for a pending approval, from its card, once. */
class PendingToolApprovalRegistryTest {
    private val registry = PendingToolApprovalRegistry().apply {
        sync(CONVERSATION, mapOf(REQUEST to listOf(CALL)))
    }

    @Test
    fun forgedButtonWithMadeUpRequestIdIsRejected() {
        val forged = approvalAction(requestId = "made-up", origin = A2uiActionOrigin.SurfaceAuthored)

        assertRejected(ToolApprovalRejection.NotFromApprovalCard, registry.admit(forged, CONVERSATION))
    }

    @Test
    fun forgedButtonCarryingRealPendingIdsIsRejected() {
        val forged = approvalAction(origin = A2uiActionOrigin.SurfaceAuthored)

        assertRejected(ToolApprovalRejection.NotFromApprovalCard, registry.admit(forged, CONVERSATION))
        // The forgery consumed nothing: the genuine card still answers.
        assertTrue(registry.admit(approvalAction(), CONVERSATION) is ToolApprovalAdmission.Admitted)
    }

    @Test
    fun cardActionWithUnknownRequestIdIsRejected() {
        assertRejected(
            ToolApprovalRejection.UnknownRequest,
            registry.admit(approvalAction(requestId = "made-up"), CONVERSATION),
        )
    }

    @Test
    fun realPendingIdWithWrongCallIdIsRejected() {
        assertRejected(
            ToolApprovalRejection.CallMismatch,
            registry.admit(approvalAction(callId = "call-other"), CONVERSATION),
        )
    }

    @Test
    fun genuineCardActionIsAdmittedOnceWithItsScope() {
        val admission = registry.admit(approvalAction(scope = "session"), CONVERSATION)

        assertEquals(
            ToolApprovalAdmission.Admitted(
                A2uiToolApprovalSubmission(REQUEST, CALL, approve = true, scope = "session", reason = null),
                CONVERSATION,
            ),
            admission,
        )
        assertRejected(ToolApprovalRejection.Retired, registry.admit(approvalAction(), CONVERSATION))
    }

    @Test
    fun answeredApprovalStaysRetiredWhileTimelineStillListsIt() {
        registry.admit(approvalAction(), CONVERSATION)
        registry.sync(CONVERSATION, mapOf(REQUEST to listOf(CALL)))

        assertRejected(ToolApprovalRejection.Retired, registry.admit(approvalAction(), CONVERSATION))
    }

    @Test
    fun approvalAfterTurnEndIsRejected() {
        registry.expire(CONVERSATION)
        registry.sync(CONVERSATION, mapOf(REQUEST to listOf(CALL)))

        assertRejected(ToolApprovalRejection.Retired, registry.admit(approvalAction(), CONVERSATION))
    }

    @Test
    fun approvalNoLongerPendingInTimelineIsRejected() {
        registry.sync(CONVERSATION, emptyMap())

        assertRejected(ToolApprovalRejection.UnknownRequest, registry.admit(approvalAction(), CONVERSATION))
    }

    @Test
    fun approvalAnsweredFromAnotherConversationIsRejected() {
        assertRejected(
            ToolApprovalRejection.ConversationMismatch,
            registry.admit(approvalAction(), "conv-other"),
        )
    }

    @Test
    fun decisionThePendingApprovalDoesNotOfferIsRejected() {
        val denyOnly = PendingToolApprovalRegistry().apply {
            register(CONVERSATION, REQUEST, CALL, setOf(ToolApprovalDecision.Deny))
        }

        assertRejected(ToolApprovalRejection.DecisionNotAllowed, denyOnly.admit(approvalAction(), CONVERSATION))
        assertTrue(denyOnly.admit(approvalAction(decision = "deny"), CONVERSATION) is ToolApprovalAdmission.Admitted)
    }

    @Test
    fun cardActionWithoutApprovalRequestIdIsRejected() {
        assertRejected(
            ToolApprovalRejection.Malformed,
            registry.admit(approvalAction(requestId = null), CONVERSATION),
        )
    }

    @Test
    fun otherActionsAreNotApprovals() {
        val button = A2uiAction(name = "show_details", surfaceId = "surface-1")

        assertNull(registry.admit(button, CONVERSATION))
        assertTrue(button.mayForwardAsUserAction())
    }

    @Test
    fun approvalAnswersAreNeverForwardedAsPlainUserActions() {
        assertEquals(false, approvalAction(origin = A2uiActionOrigin.SurfaceAuthored).mayForwardAsUserAction())
        assertEquals(false, approvalAction().mayForwardAsUserAction())
    }

    private fun assertRejected(expected: ToolApprovalRejection, admission: ToolApprovalAdmission?) {
        assertEquals(ToolApprovalAdmission.Rejected(expected), admission)
    }

    private fun approvalAction(
        requestId: String? = REQUEST,
        callId: String = CALL,
        decision: String = "approve",
        scope: String = "once",
        origin: A2uiActionOrigin = A2uiActionOrigin.ToolApprovalCard,
    ): A2uiAction {
        val context = buildJsonObject {
            requestId?.let { put("approvalRequestId", it) }
            put("callId", callId)
            put("decision", decision)
            put("scope", scope)
        }
        return A2uiAction(
            name = A2UI_TOOL_APPROVAL_RESPONSE_ACTION,
            surfaceId = "surface-1",
            context = context,
            conversationId = CONVERSATION,
            actionId = callId,
            origin = origin,
        )
    }

    private companion object {
        const val CONVERSATION = "conv-1"
        const val REQUEST = "approval-1"
        const val CALL = "call-1"
    }
}
