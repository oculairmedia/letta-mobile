package com.letta.mobile.data.repository.iroh

import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class IrohSlashCommandApiTest {

    @Test
    fun listAgentSlashCommandsDecodesAdminRpcPayload() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeIrohAdminTransport()
        transport.rpcResponder = { call ->
            assertEquals("slash_command.list_agent", call.method)
            assertEquals("/v1/agents/agent-1/slash-commands", call.path)
            assertEquals("""{"agent_id":"agent-1"}""", call.body)
            ok(
                """
                {
                  "commands": [
                    {
                      "command": "/goal",
                      "name": "goal",
                      "description": "Goal mode",
                      "source": "builtin",
                      "installed": true
                    },
                    {
                      "command": "/review",
                      "name": "review",
                      "description": "Review skill",
                      "skill_name": "review",
                      "source": "skill",
                      "installed": true
                    }
                  ]
                }
                """.trimIndent(),
            )
        }
        val api = IrohSlashCommandApi(IrohAdminRpcAgentDirectory(transport))

        val commands = api.listAgentSlashCommands("agent-1")

        assertEquals(listOf("goal", "review"), commands.map { it.command })
        assertEquals("review", commands[1].skillName)
    }

    private fun ok(resultJson: String) = AppServerInboundFrame.AdminRpcResponse(
        requestId = "req-1",
        success = true,
        result = Json.parseToJsonElement(resultJson),
    )
}
