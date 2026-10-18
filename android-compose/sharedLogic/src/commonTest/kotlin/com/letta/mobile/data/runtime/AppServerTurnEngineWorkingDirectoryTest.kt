package com.letta.mobile.data.runtime

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * letta-mobile-k5nbq.9 / k5nbq.12: an agent's saved default cwd opens a conversation the runtime
 * keeps no working directory for, and never replaces one that was chosen for it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppServerTurnEngineWorkingDirectoryTest {
    @Test
    fun aNewConversationStartsInTheAgentsDefaultCwd() = runTest {
        val client = RecordingCwdClient(cwdMap = mapOf("conversation:other" to "/elsewhere"))
        startTurn(client, defaultsWith("/work/repo"))
        assertEquals("/work/repo", client.cwd)
    }

    @Test
    fun aConversationWithAChosenCwdKeepsItWhenItsRuntimeStartsAgain() = runTest {
        // The first turn after a restart: the engine has no runtime cached for a conversation
        // whose folder was picked earlier, and the runtime still holds that folder.
        val client = RecordingCwdClient(cwdMap = mapOf("conversation:conv-1" to "/picked"))
        startTurn(client, defaultsWith("/work/repo"))
        assertNull(client.cwd)
    }

    @Test
    fun noCwdIsSentWhenTheCwdMapCannotBeRead() = runTest {
        val client = RecordingCwdClient(cwdMap = null)
        startTurn(client, defaultsWith("/work/repo"))
        assertNull(client.cwd)
    }

    @Test
    fun noCwdIsSentWhenTheAgentHasNoDefault() = runTest {
        val client = RecordingCwdClient(cwdMap = emptyMap())
        startTurn(client, StoredAgentDefaults(InMemorySecureSettingsStore()))
        assertNull(client.cwd)
    }

    private fun TestScope.startTurn(client: RecordingCwdClient, defaults: AgentDefaultsRepository) {
        val engine = AppServerTurnEngine(
            client = client,
            nowMs = { testScheduler.currentTime },
            agentDefaults = defaults,
        )
        backgroundScope.launch { engine.runTurn(COMMAND).collect {} }
        runCurrent()
    }

    private fun defaultsWith(cwd: String) =
        StoredAgentDefaults(InMemorySecureSettingsStore()).apply { setCwd(COMMAND.agentId, cwd) }

    /** Answers `get_cwd_map` with [cwdMap], or refuses it when null; records the started cwd. */
    private class RecordingCwdClient(private val cwdMap: Map<String, String>?) : TurnEngineTestStreamClient() {
        var cwd: String? = "unset"

        override suspend fun getCwdMap(command: AppServerCommand.GetCwdMap) =
            AppServerInboundFrame.GetCwdMapResponse(
                requestId = command.requestId,
                success = cwdMap != null,
                cwdMap = cwdMap.orEmpty(),
            )

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
