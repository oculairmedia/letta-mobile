package com.letta.mobile.data.a2ui

import com.letta.mobile.data.model.ConversationId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** letta-mobile-by4vm.2: an A2UI approval answer is admitted only for a pending approval, from its card, once. */
class PendingToolApprovalRegistryTest {
    private val registry = PendingToolApprovalRegistry().apply {
        sync(CONVERSATION, setOf(PENDING))
    }

    @Test
    fun forgedButtonWithMadeUpRequestIdIsRejected() {
        val forged = approvalAction(Answer(requestId = "made-up", origin = A2uiActionOrigin.SurfaceAuthored))

        assertRejected(ToolApprovalRejection.NotFromApprovalCard, registry.admit(forged, CONVERSATION))
    }

    @Test
    fun forgedButtonCarryingRealPendingIdsIsRejected() {
        val forged = approvalAction(Answer(origin = A2uiActionOrigin.SurfaceAuthored))

        assertRejected(ToolApprovalRejection.NotFromApprovalCard, registry.admit(forged, CONVERSATION))
        // The forgery consumed nothing: the genuine card still answers.
        assertTrue(registry.admit(approvalAction(), CONVERSATION) is ToolApprovalAdmission.Admitted)
    }

    @Test
    fun cardActionWithUnknownRequestIdIsRejected() {
        assertRejected(
            ToolApprovalRejection.UnknownRequest,
            registry.admit(approvalAction(Answer(requestId = "made-up")), CONVERSATION),
        )
    }

    @Test
    fun realPendingIdWithWrongCallIdIsRejected() {
        assertRejected(
            ToolApprovalRejection.CallMismatch,
            registry.admit(approvalAction(Answer(callId = "call-other")), CONVERSATION),
        )
    }

    @Test
    fun genuineCardActionIsAdmittedOnceWithItsScope() {
        val admission = registry.admit(approvalAction(Answer(scope = "session")), CONVERSATION)

        assertEquals(
            ToolApprovalAdmission.Admitted(
                A2uiToolApprovalSubmission(PENDING, approve = true, scope = "session", reason = null),
                CONVERSATION,
            ),
            admission,
        )
        assertRejected(ToolApprovalRejection.Retired, registry.admit(approvalAction(), CONVERSATION))
    }

    @Test
    fun answeredApprovalStaysRetiredWhileTimelineStillListsIt() {
        registry.admit(approvalAction(), CONVERSATION)
        registry.sync(CONVERSATION, setOf(PENDING))

        assertRejected(ToolApprovalRejection.Retired, registry.admit(approvalAction(), CONVERSATION))
    }

    @Test
    fun approvalAfterTurnEndIsRejected() {
        registry.expire(CONVERSATION)
        registry.sync(CONVERSATION, setOf(PENDING))

        assertRejected(ToolApprovalRejection.Retired, registry.admit(approvalAction(), CONVERSATION))
    }

    @Test
    fun approvalNoLongerPendingInTimelineIsRejected() {
        registry.sync(CONVERSATION, emptySet())

        assertRejected(ToolApprovalRejection.UnknownRequest, registry.admit(approvalAction(), CONVERSATION))
    }

    @Test
    fun approvalAnsweredFromAnotherConversationIsRejected() {
        assertRejected(
            ToolApprovalRejection.ConversationMismatch,
            registry.admit(approvalAction(), ConversationId("conv-other")),
        )
    }

    @Test
    fun decisionThePendingApprovalDoesNotOfferIsRejected() {
        val denyOnly = PendingToolApprovalRegistry().apply {
            register(CONVERSATION, PENDING, setOf(ToolApprovalDecision.Deny))
        }

        assertRejected(ToolApprovalRejection.DecisionNotAllowed, denyOnly.admit(approvalAction(), CONVERSATION))
        assertTrue(denyOnly.admit(approvalAction(Answer(decision = "deny")), CONVERSATION) is ToolApprovalAdmission.Admitted)
    }

    @Test
    fun cardActionWithoutApprovalRequestIdIsRejected() {
        assertRejected(
            ToolApprovalRejection.Malformed,
            registry.admit(approvalAction(Answer(requestId = null)), CONVERSATION),
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
        assertEquals(false, approvalAction(Answer(origin = A2uiActionOrigin.SurfaceAuthored)).mayForwardAsUserAction())
        assertEquals(false, approvalAction().mayForwardAsUserAction())
    }

    private fun assertRejected(expected: ToolApprovalRejection, admission: ToolApprovalAdmission?) {
        assertEquals(ToolApprovalAdmission.Rejected(expected), admission)
    }

    /** The fields a test varies on an approval answer; defaults are the genuine card's answer. */
    private data class Answer(
        val requestId: String? = REQUEST,
        val callId: String = CALL,
        val decision: String = "approve",
        val scope: String = "once",
        val origin: A2uiActionOrigin = A2uiActionOrigin.ToolApprovalCard,
    )

    private fun approvalAction(answer: Answer = Answer()): A2uiAction {
        val context = buildJsonObject {
            answer.requestId?.let { put("approvalRequestId", it) }
            put("callId", answer.callId)
            put("decision", answer.decision)
            put("scope", answer.scope)
        }
        return A2uiAction(
            name = A2UI_TOOL_APPROVAL_RESPONSE_ACTION,
            surfaceId = "surface-1",
            context = context,
            conversationId = CONVERSATION.value,
            actionId = answer.callId,
            origin = answer.origin,
        )
    }

    private companion object {
        const val REQUEST = "approval-1"
        const val CALL = "call-1"
        val CONVERSATION = ConversationId("conv-1")
        val PENDING = ToolApprovalRef.of(REQUEST, CALL)!!
    }
}
