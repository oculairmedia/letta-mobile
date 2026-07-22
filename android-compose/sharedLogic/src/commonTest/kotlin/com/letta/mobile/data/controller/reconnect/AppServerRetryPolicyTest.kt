package com.letta.mobile.data.controller.reconnect

import com.letta.mobile.data.transport.appserver.AppServerApprovalResponseDecision
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerExternalToolResult
import com.letta.mobile.data.transport.appserver.AppServerInputMessage
import com.letta.mobile.data.transport.appserver.AppServerInputPayload
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive

class AppServerRetryPolicyTest {
    private val runtime = AppServerRuntimeScope(agentId = "agent-1", conversationId = "conv-1")

    @Test
    fun reattachAndSyncAreSafeReads() {
        assertEquals(
            AppServerRetryClass.SAFE_READ,
            AppServerRetryPolicy.classify(
                AppServerCommand.RuntimeStart(requestId = "r1", agentId = "agent-1", conversationId = "conv-1"),
            ),
        )
        assertEquals(
            AppServerRetryClass.SAFE_READ,
            AppServerRetryPolicy.classify(AppServerCommand.Sync(runtime = runtime, requestId = "r2")),
        )
    }

    @Test
    fun turnInputIsAnAmbiguousWriteNeverBlindlyReplayed() {
        val input = AppServerCommand.Input(
            runtime = runtime,
            payload = AppServerInputPayload.CreateMessage(
                messages = listOf(
                    AppServerInputMessage(role = "user", content = JsonPrimitive("hi"), clientMessageId = "cmid-1"),
                ),
            ),
        )
        assertEquals(AppServerRetryClass.AMBIGUOUS_WRITE, AppServerRetryPolicy.classify(input))
    }

    @Test
    fun approvalAbortAndExternalToolResultsAreNonIdempotentControl() {
        val approval = AppServerCommand.Input(
            runtime = runtime,
            payload = AppServerInputPayload.ApprovalResponse(
                requestId = "appr-1",
                decision = AppServerApprovalResponseDecision.Allow(message = "ok"),
            ),
        )
        assertEquals(AppServerRetryClass.NON_IDEMPOTENT_CONTROL, AppServerRetryPolicy.classify(approval))
        assertEquals(
            AppServerRetryClass.NON_IDEMPOTENT_CONTROL,
            AppServerRetryPolicy.classify(AppServerCommand.AbortMessage(runtime = runtime, requestId = "r3")),
        )
        assertEquals(
            AppServerRetryClass.NON_IDEMPOTENT_CONTROL,
            AppServerRetryPolicy.classify(
                AppServerCommand.ExternalToolCallResponse(
                    requestId = "tc-1",
                    result = AppServerExternalToolResult(content = emptyList()),
                ),
            ),
        )
    }

    @Test
    fun adminRpcReadsRetrySafelyAndUnknownMethodsAreAmbiguous() {
        assertEquals(AppServerRetryClass.SAFE_READ, AppServerRetryPolicy.classifyAdminRpc("agent.list"))
        assertEquals(AppServerRetryClass.SAFE_READ, AppServerRetryPolicy.classifyAdminRpc("conversation.get"))
        assertEquals(AppServerRetryClass.SAFE_READ, AppServerRetryPolicy.classifyAdminRpc("health.check"))
        assertEquals(AppServerRetryClass.SAFE_READ, AppServerRetryPolicy.classifyAdminRpc("skill.list_agent"))
        assertEquals(AppServerRetryClass.SAFE_READ, AppServerRetryPolicy.classifyAdminRpc("model.list.embedding"))
        assertEquals(AppServerRetryClass.SAFE_READ, AppServerRetryPolicy.classifyAdminRpc("project.beadsRemoteStatus"))
        assertEquals(AppServerRetryClass.AMBIGUOUS_WRITE, AppServerRetryPolicy.classifyAdminRpc("agent.create"))
        assertEquals(AppServerRetryClass.AMBIGUOUS_WRITE, AppServerRetryPolicy.classifyAdminRpc("approval.submit"))
        assertEquals(AppServerRetryClass.AMBIGUOUS_WRITE, AppServerRetryPolicy.classifyAdminRpc("no.such.method"))
    }

    @Test
    fun reconcilerResendsOnlyWhenTheInputNeverCommitted() = runTest {
        val reconciler = AmbiguousTurnReconciler(
            inspector = { _, clientMessageId ->
                when (clientMessageId) {
                    "committed-complete" -> CommittedTurnState(assistantTurnCompleted = true)
                    "committed-mid-turn" -> CommittedTurnState(assistantTurnCompleted = false)
                    else -> null
                }
            },
        )

        assertEquals(AmbiguousTurnResolution.ResendSafe, reconciler.resolve(runtime, "never-committed"))
        assertEquals(AmbiguousTurnResolution.AlreadyCompleted, reconciler.resolve(runtime, "committed-complete"))
        assertEquals(
            AmbiguousTurnResolution.CommittedWithoutAssistantTurn,
            reconciler.resolve(runtime, "committed-mid-turn"),
        )
    }
}

/** SAM-style helper so tests can pass a lambda inspector. */
private fun AmbiguousTurnReconciler(
    inspector: suspend (AppServerRuntimeScope, String) -> CommittedTurnState?,
): AmbiguousTurnReconciler = AmbiguousTurnReconciler(
    object : CommittedTranscriptInspector {
        override suspend fun committedTurnState(
            runtime: AppServerRuntimeScope,
            clientMessageId: String,
        ): CommittedTurnState? = inspector(runtime, clientMessageId)
    },
)
