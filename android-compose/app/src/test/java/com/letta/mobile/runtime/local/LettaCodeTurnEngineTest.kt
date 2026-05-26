package com.letta.mobile.runtime.local

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LettaCodeTurnEngineTest {
    @Test
    fun `failed client startup becomes failed runtime lifecycle`() = runTest {
        val engine = LettaCodeTurnEngine(
            client = object : LettaCodeHeadlessClient {
                override fun runTurn(command: TurnCommand): Flow<RuntimeEventDraft> = flow {
                    throw IllegalStateException("Embedded LettaCode is disabled in this build.")
                }
            },
        )

        val events = engine.runTurn(command()).toList()

        val running = events[0].payload as RuntimeEventPayload.RunLifecycleChanged
        val failed = events[1].payload as RuntimeEventPayload.RunLifecycleChanged
        assertEquals(RuntimeRunStatus.Running, running.status)
        assertEquals(RuntimeRunStatus.Failed, failed.status)
        assertEquals("Embedded LettaCode is disabled in this build.", failed.reason)
    }

    private fun command(): TurnCommand = TurnCommand(
        backendId = BackendId("local-lettacode:test"),
        runtimeId = RuntimeId("local-lettacode:test"),
        agentId = AgentId("agent-1"),
        conversationId = ConversationId("conv-1"),
        input = TurnInput.UserMessage(
            localMessageId = "local-1",
            text = "hello",
        ),
    )
}
