package com.letta.mobile.data.runtime

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** letta-mobile-k5nbq.9: a saved default cwd is the directory `runtime_start` opens. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppServerTurnEngineWorkingDirectoryTest {
    @Test
    fun runtimeStartCarriesTheProvidedCwd() = runTest {
        val client = RecordingCwdClient()
        val engine = AppServerTurnEngine(
            client = client,
            nowMs = { testScheduler.currentTime },
        ).apply { workingDirectoryProvider = { "/work/repo" } }
        backgroundScope.launch { engine.runTurn(COMMAND).collect {} }
        runCurrent()
        assertEquals("/work/repo", client.cwd)
    }

    @Test
    fun runtimeStartOmitsCwdWhenTheProviderHasNone() = runTest {
        val client = RecordingCwdClient()
        val engine = AppServerTurnEngine(
            client = client,
            nowMs = { testScheduler.currentTime },
        )
        backgroundScope.launch { engine.runTurn(COMMAND).collect {} }
        runCurrent()
        assertNull(client.cwd)
    }

    private class RecordingCwdClient : TurnEngineTestStreamClient() {
        var cwd: String? = "unset"

        override suspend fun runtimeStart(
            command: AppServerCommand.RuntimeStart,
        ): AppServerInboundFrame.RuntimeStartResponse {
            cwd = command.cwd
            return super.runtimeStart(command)
        }
    }

    private companion object {
        val COMMAND = TurnCommand(
            backendId = BackendId("backend-1"),
            runtimeId = RuntimeId("runtime-1"),
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
            input = TurnInput.UserMessage(localMessageId = "local-1", text = "hello"),
        )
    }
}
