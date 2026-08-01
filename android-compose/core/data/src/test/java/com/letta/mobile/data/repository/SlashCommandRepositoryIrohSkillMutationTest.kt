package com.letta.mobile.data.repository

import com.letta.mobile.data.api.LettaApiClient
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.testutil.FakeChannelTransport
import com.letta.mobile.testutil.FakeSettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlashCommandRepositoryIrohSkillMutationTest {
    private val json = Json

    @Test
    fun `install routes over iroh with exact method path and body`() = runTest {
        val (repository, apiClient, transport) = repositoryWithIroh()

        val result = repository.installToAgent("agent-1", "calendar")

        assertTrue(result.isSuccess)
        val call = transport.adminRpcCalls.single()
        assertEquals("skill.install", call.method)
        assertEquals("/v1/agents/agent-1/skills", call.path)
        assertSkillBody(call.body, "agent-1", "calendar")
        coVerify(exactly = 0) { apiClient.session() }
    }

    @Test
    fun `uninstall routes over iroh with exact method path and body`() = runTest {
        val (repository, apiClient, transport) = repositoryWithIroh()

        val result = repository.uninstallFromAgent("agent-1", "calendar")

        assertTrue(result.isSuccess)
        val call = transport.adminRpcCalls.single()
        assertEquals("skill.uninstall", call.method)
        assertEquals("/v1/agents/agent-1/skills/calendar", call.path)
        assertSkillBody(call.body, "agent-1", "calendar")
        coVerify(exactly = 0) { apiClient.session() }
    }

    @Test
    fun `iroh skill mutation failure is returned loudly`() = runTest {
        val (repository, _, transport) = repositoryWithIroh(success = false, error = "native mutation rejected")

        val install = repository.installToAgent("agent-1", "calendar")
        val uninstall = repository.uninstallFromAgent("agent-1", "calendar")

        assertTrue(install.isFailure)
        assertTrue(install.exceptionOrNull() is IllegalStateException)
        assertEquals("native mutation rejected", install.exceptionOrNull()?.message)
        assertTrue(uninstall.isFailure)
        assertTrue(uninstall.exceptionOrNull() is IllegalStateException)
        assertEquals("native mutation rejected", uninstall.exceptionOrNull()?.message)
        assertEquals(listOf("skill.install", "skill.uninstall"), transport.adminRpcCalls.map { it.method })
    }

    private fun repositoryWithIroh(
        success: Boolean = true,
        error: String? = null,
    ): Triple<SlashCommandRepository, LettaApiClient, FakeChannelTransport> {
        val settings = FakeSettingsRepository(
            initialActiveConfig = LettaConfig(
                id = "iroh",
                mode = LettaConfig.Mode.SELF_HOSTED,
                serverUrl = "iroh://EndpointTicket",
            ),
        )
        val apiClient = mockk<LettaApiClient>()
        coEvery { apiClient.session() } throws AssertionError("HTTP path invoked")
        val transport = FakeChannelTransport().apply {
            adminRpcHandler = { _, _, _ ->
                AppServerInboundFrame.AdminRpcResponse(
                    requestId = "req",
                    success = success,
                    error = error,
                )
            }
        }
        return Triple(SlashCommandRepository(apiClient, settings, transport), apiClient, transport)
    }

    private fun assertSkillBody(bodyText: String?, agentId: String, skillName: String) {
        val body = json.parseToJsonElement(requireNotNull(bodyText)).jsonObject
        assertEquals(setOf("agent_id", "name"), body.keys)
        assertEquals(agentId, body.getValue("agent_id").jsonPrimitive.content)
        assertEquals(skillName, body.getValue("name").jsonPrimitive.content)
    }
}
