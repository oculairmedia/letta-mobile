package com.letta.mobile.data.controller.reconnect

import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class AmbiguousTurnReconcilerTest {
    private val runtime = AppServerRuntimeScope(agentId = "agent-1", conversationId = "conv-1")

    @Test
    fun resendsOnlyWhenTheInputNeverCommitted() = runTest {
        val reconciler = AmbiguousTurnReconciler(
            inspector = object : CommittedTranscriptInspector {
                override suspend fun committedTurnState(
                    runtime: AppServerRuntimeScope,
                    clientMessageId: String,
                ): CommittedTurnState? = when (clientMessageId) {
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
