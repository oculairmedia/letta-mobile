package com.letta.mobile.data.repository

import app.cash.turbine.test
import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.api.PassageApi
import com.letta.mobile.data.model.Passage
import com.letta.mobile.data.model.PassageCreateParams
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.testutil.FakeChannelTransport
import com.letta.mobile.testutil.FakeSettingsRepository
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class PassageRepositoryTest {
    private lateinit var fakeApi: FakePassageApi
    private lateinit var repository: PassageRepository

    @Before
    fun setup() {
        fakeApi = FakePassageApi()
        repository = PassageRepository(fakeApi)
    }

    @Test
    fun `getPassages emits refresh updates`() = runTest {
        val passage = Passage(id = "p1", text = "Some knowledge", agentId = "a1")

        repository.getPassages("a1").test {
            assertEquals(emptyList<Passage>(), awaitItem())

            fakeApi.setPassages("a1", listOf(passage))
            repository.refreshPassages("a1")

            assertEquals(listOf(passage), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `createPassage emits refreshed cache`() = runTest {
        repository.getPassages("a1").test {
            assertEquals(emptyList<Passage>(), awaitItem())

            val created = repository.createPassage("a1", "New passage")

            assertEquals("New passage", created.text)
            assertEquals(listOf(created), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `create and delete passage route through admin rpc in iroh mode`() = runTest {
        val transport = FakeChannelTransport().apply {
            adminRpcHandler = { method, _, _ ->
                val result = if (method == "passage.create") {
                    "{\"id\":\"passage-1\",\"text\":\"New passage\",\"agent_id\":\"agent-1\"}"
                } else {
                    "{}"
                }
                AppServerInboundFrame.AdminRpcResponse("req", true, Json.parseToJsonElement(result))
            }
        }
        val irohRepository = PassageRepository(
            passageApi = fakeApi,
            irohPassageSource = IrohAdminRpcPassageSource(transport, irohSettings()),
        )

        val created = irohRepository.createPassage("agent-1", "New passage")
        irohRepository.deletePassage("agent-1", "passage-1")

        assertTrue(fakeApi.calls.none { it.startsWith("createPassage") || it.startsWith("deletePassage") })
        assertEquals(listOf("passage.create", "passage.delete"), transport.adminRpcCalls.map { it.method })
        assertEquals("/v1/agents/agent-1/archival-memory", transport.adminRpcCalls[0].path)
        assertTrue(transport.adminRpcCalls[0].body.orEmpty().contains("\"text\":\"New passage\""))
        assertEquals("/v1/agents/agent-1/archival-memory/passage-1", transport.adminRpcCalls[1].path)
        assertEquals("passage-1", created.id)
    }

    private fun irohSettings() = FakeSettingsRepository(
        initialActiveConfig = LettaConfig(
            id = "iroh",
            mode = LettaConfig.Mode.SELF_HOSTED,
            serverUrl = "iroh://EndpointTicket",
        ),
    )

    @Test
    fun `deletePassage emits removal from cache`() = runTest {
        val first = Passage(id = "p1", text = "First", agentId = "a1")
        val second = Passage(id = "p2", text = "Second", agentId = "a1")
        fakeApi.setPassages("a1", listOf(first, second))
        repository.refreshPassages("a1")

        repository.getPassages("a1").test {
            assertEquals(listOf(first, second), awaitItem())

            repository.deletePassage("a1", "p1")

            assertEquals(listOf(second), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    private class FakePassageApi : PassageApi(mockk(relaxed = true)) {
        private val passagesByAgent = mutableMapOf<String, MutableList<Passage>>()
        var shouldFail = false
        val calls = mutableListOf<String>()

        fun setPassages(agentId: String, passages: List<Passage>) {
            passagesByAgent[agentId] = passages.toMutableList()
        }

        override suspend fun listPassages(
            agentId: String,
            limit: Int?,
            after: String?,
            search: String?,
        ): List<Passage> {
            calls.add("listPassages:$agentId")
            if (shouldFail) throw ApiException(500, "Server error")
            return passagesByAgent[agentId]
                .orEmpty()
                .filter { passage -> search == null || passage.text.contains(search, ignoreCase = true) }
                .take(limit ?: Int.MAX_VALUE)
        }

        override suspend fun createPassage(agentId: String, params: PassageCreateParams): Passage {
            calls.add("createPassage:$agentId")
            if (shouldFail) throw ApiException(500, "Server error")
            val passages = passagesByAgent.getOrPut(agentId) { mutableListOf() }
            val passage = Passage(
                id = "p${passages.size + 1}",
                text = params.text,
                agentId = agentId,
            )
            passages += passage
            return passage
        }

        override suspend fun deletePassage(agentId: String, passageId: String) {
            calls.add("deletePassage:$agentId:$passageId")
            if (shouldFail) throw ApiException(500, "Server error")
            passagesByAgent[agentId]?.removeAll { it.id == passageId }
        }

        override suspend fun searchArchival(
            agentId: String,
            query: String,
            limit: Int?,
        ): List<Passage> {
            calls.add("searchArchival:$agentId")
            return listPassages(agentId = agentId, limit = limit, after = null, search = query)
        }
    }
}
