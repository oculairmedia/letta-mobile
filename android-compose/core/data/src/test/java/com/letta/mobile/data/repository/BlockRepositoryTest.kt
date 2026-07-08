package com.letta.mobile.data.repository

import com.letta.mobile.data.api.IrohAdminApiUnavailableException
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockCreateParams
import com.letta.mobile.data.model.BlockId
import com.letta.mobile.data.model.BlockUpdateParams
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.testutil.FakeBlockApi
import com.letta.mobile.testutil.FakeChannelTransport
import com.letta.mobile.testutil.FakeSettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class BlockRepositoryTest {

    private lateinit var fakeApi: FakeBlockApi
    private lateinit var repository: BlockRepository

    @Before
    fun setup() {
        fakeApi = FakeBlockApi()
        repository = BlockRepository(fakeApi)
    }

    @Test
    fun `updateAgentBlock calls API with correct params`() = runTest {
        repository.updateAgentBlock(
            "a1",
            "persona",
            BlockUpdateParams(value = "New persona value", description = "desc", limit = 256)
        )
        assertTrue(fakeApi.calls.contains("updateAgentBlock:a1:persona"))
        assertEquals("desc", fakeApi.lastUpdateParams?.description)
        assertEquals(256, fakeApi.lastUpdateParams?.limit)
    }

    @Test
    fun `updateAgentBlock returns updated block`() = runTest {
        val result = repository.updateAgentBlock("a1", "human", BlockUpdateParams(value = "Updated human block"))
        assertEquals("human", result.label)
        assertEquals("Updated human block", result.value)
    }

    @Test
    fun `updateGlobalBlock calls global API endpoint`() = runTest {
        fakeApi.allBlocks.add(Block(id = BlockId("b1"), label = "persona", value = "Old value"))

        val result = repository.updateGlobalBlock("b1", BlockUpdateParams(value = "New value", description = "desc"))

        assertTrue(fakeApi.calls.contains("updateGlobalBlock:b1:false:false"))
        assertEquals("New value", result.value)
        assertEquals("desc", result.description)
    }

    @Test
    fun `updateGlobalBlock can clear optional metadata`() = runTest {
        fakeApi.allBlocks.add(Block(id = BlockId("b1"), label = "persona", value = "Old value", description = "desc", limit = 42))

        val result = repository.updateGlobalBlock(
            "b1",
            BlockUpdateParams(value = "New value"),
            clearDescription = true,
            clearLimit = true,
        )

        assertTrue(fakeApi.calls.contains("updateGlobalBlock:b1:true:true"))
        assertEquals("New value", result.value)
        assertEquals(null, result.description)
        assertEquals(null, result.limit)
    }

    @Test(expected = com.letta.mobile.data.api.ApiException::class)
    fun `updateAgentBlock throws on API failure`() = runTest {
        fakeApi.shouldFail = true
        repository.updateAgentBlock("a1", "persona", BlockUpdateParams(value = "value"))
    }

    @Test
    fun `listAllBlocks returns all blocks`() = runTest {
        fakeApi.allBlocks.add(Block(id = BlockId("b1"), label = "persona", value = "Test persona"))
        fakeApi.allBlocks.add(Block(id = BlockId("b2"), label = "human", value = "Test human"))
        val result = repository.listAllBlocks()
        assertEquals(2, result.size)
        assertTrue(fakeApi.calls.contains("listAllBlocks"))
    }

    @Test
    fun `listAllBlocks filters by label`() = runTest {
        fakeApi.allBlocks.add(Block(id = BlockId("b1"), label = "persona", value = "Test persona"))
        fakeApi.allBlocks.add(Block(id = BlockId("b2"), label = "human", value = "Test human"))
        val result = repository.listAllBlocks(label = "persona")
        assertEquals(1, result.size)
        assertEquals("persona", result[0].label)
    }

    @Test
    fun `listAllBlocks filters by isTemplate`() = runTest {
        fakeApi.allBlocks.add(Block(id = BlockId("b1"), label = "persona", value = "Template", isTemplate = true))
        fakeApi.allBlocks.add(Block(id = BlockId("b2"), label = "human", value = "Not template", isTemplate = false))
        val result = repository.listAllBlocks(isTemplate = true)
        assertEquals(1, result.size)
        assertEquals(true, result[0].isTemplate)
    }

    @Test(expected = com.letta.mobile.data.api.ApiException::class)
    fun `listAllBlocks throws on API failure`() = runTest {
        fakeApi.shouldFail = true
        repository.listAllBlocks()
    }

    // ─── Iroh Purity Tests (letta-mobile client batch) ────────────────────────

    @Test(expected = IrohAdminApiUnavailableException::class)
    fun `retrieveBlock in iroh mode without source throws IrohAdminApiUnavailableException`() = runTest {
        // RED: repository has no irohBlockSource yet, so raw API call throws
        val settings = FakeSettingsRepository(
            initialActiveConfig = LettaConfig(
                id = "test",
                mode = LettaConfig.Mode.SELF_HOSTED,
                serverUrl = "iroh://test-node",
                accessToken = "token",
            )
        )
        val apiThatThrows = object : FakeBlockApi() {
            override suspend fun retrieveBlock(blockId: String): Block {
                throw IrohAdminApiUnavailableException("Raw HTTP forbidden in iroh:// mode")
            }
        }
        val repo = BlockRepository(apiThatThrows)
        repo.retrieveBlock("b1")
    }

    @Test
    fun `retrieveBlock in iroh mode routes via admin_rpc`() = runTest {
        val settings = FakeSettingsRepository(
            initialActiveConfig = LettaConfig(
                id = "test",
                mode = LettaConfig.Mode.SELF_HOSTED,
                serverUrl = "iroh://test-node",
                accessToken = "token",
            )
        )
        val transport = FakeChannelTransport()
        val testBlock = Block(id = BlockId("b1"), label = "persona", value = "Test persona")
        transport.adminRpcHandler = { method, path, body ->
            assertEquals("block.get", method)
            assertEquals("/v1/blocks/b1", path)
            val json = Json { ignoreUnknownKeys = true }
            AppServerInboundFrame.AdminRpcResponse(
                requestId = "req",
                success = true,
                result = json.encodeToJsonElement(Block.serializer(), testBlock),
                error = null,
            )
        }
        val irohSource = IrohAdminRpcBlockSource(transport, settings)
        val apiThatThrows = object : FakeBlockApi() {
            override suspend fun retrieveBlock(blockId: String): Block {
                throw IrohAdminApiUnavailableException("Raw HTTP forbidden")
            }
        }
        // GREEN: wired repository routes via irohSource instead of raw API
        val repo = BlockRepository(apiThatThrows, irohSource)
        val result = repo.retrieveBlock("b1")
        assertEquals("b1", result.id.value)
        assertEquals(1, transport.adminRpcCalls.size)
    }

    @Test
    fun `updateGlobalBlock in iroh mode routes via admin_rpc`() = runTest {
        val settings = FakeSettingsRepository(
            initialActiveConfig = LettaConfig(id = "test", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "iroh://test", accessToken = "t")
        )
        val transport = FakeChannelTransport()
        val updatedBlock = Block(id = BlockId("b1"), label = "persona", value = "New value", description = "New desc")
        transport.adminRpcHandler = { method, path, body ->
            assertEquals("block.update", method)
            assertEquals("/v1/blocks/b1", path)
            val json = Json { ignoreUnknownKeys = true }
            AppServerInboundFrame.AdminRpcResponse(
                requestId = "req",
                success = true,
                result = json.encodeToJsonElement(Block.serializer(), updatedBlock),
                error = null,
            )
        }
        val irohSource = IrohAdminRpcBlockSource(transport, settings)
        val apiThatThrows = object : FakeBlockApi() {
            override suspend fun updateGlobalBlock(
                blockId: String,
                params: BlockUpdateParams,
                clearDescription: Boolean,
                clearLimit: Boolean,
            ): Block {
                throw IrohAdminApiUnavailableException("Raw HTTP forbidden")
            }
        }
        val repo = BlockRepository(apiThatThrows, irohSource)
        val result = repo.updateGlobalBlock("b1", BlockUpdateParams(value = "New value", description = "New desc"))
        assertEquals("New value", result.value)
        assertEquals(1, transport.adminRpcCalls.size)
    }

    @Test
    fun `createBlock in iroh mode routes via admin_rpc`() = runTest {
        val settings = FakeSettingsRepository(
            initialActiveConfig = LettaConfig(id = "test", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "iroh://test", accessToken = "t")
        )
        val transport = FakeChannelTransport()
        val createdBlock = Block(id = BlockId("b1"), label = "persona", value = "Created block")
        transport.adminRpcHandler = { method, _, _ ->
            assertEquals("block.create", method)
            val json = Json { ignoreUnknownKeys = true }
            AppServerInboundFrame.AdminRpcResponse(
                requestId = "req",
                success = true,
                result = json.encodeToJsonElement(Block.serializer(), createdBlock),
                error = null,
            )
        }
        val irohSource = IrohAdminRpcBlockSource(transport, settings)
        val apiThatThrows = object : FakeBlockApi() {
            override suspend fun createBlock(params: BlockCreateParams): Block {
                throw IrohAdminApiUnavailableException("Raw HTTP forbidden")
            }
        }
        val repo = BlockRepository(apiThatThrows, irohSource)
        val result = repo.createBlock(BlockCreateParams(label = "persona", value = "Created block"))
        assertEquals("Created block", result.value)
        assertEquals(1, transport.adminRpcCalls.size)
    }

    @Test
    fun `deleteBlock in iroh mode routes via admin_rpc`() = runTest {
        val settings = FakeSettingsRepository(
            initialActiveConfig = LettaConfig(id = "test", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "iroh://test", accessToken = "t")
        )
        val transport = FakeChannelTransport()
        transport.adminRpcHandler = { method, path, _ ->
            assertEquals("block.delete", method)
            assertEquals("/v1/blocks/b1", path)
            AppServerInboundFrame.AdminRpcResponse(requestId = "req", success = true, result = JsonNull, error = null)
        }
        val irohSource = IrohAdminRpcBlockSource(transport, settings)
        val apiThatThrows = object : FakeBlockApi() {
            override suspend fun deleteBlock(blockId: String) {
                throw IrohAdminApiUnavailableException("Raw HTTP forbidden")
            }
        }
        val repo = BlockRepository(apiThatThrows, irohSource)
        repo.deleteBlock("b1")
        assertEquals(1, transport.adminRpcCalls.size)
    }

    @Test
    fun `listAllBlocks in iroh mode routes via admin_rpc`() = runTest {
        val settings = FakeSettingsRepository(
            initialActiveConfig = LettaConfig(id = "test", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "iroh://test", accessToken = "t")
        )
        val transport = FakeChannelTransport()
        val blocks = listOf(
            Block(id = BlockId("b1"), label = "persona", value = "Block 1"),
            Block(id = BlockId("b2"), label = "human", value = "Block 2"),
        )
        transport.adminRpcHandler = { method, _, _ ->
            assertEquals("block.list", method)
            val json = Json { ignoreUnknownKeys = true }
            AppServerInboundFrame.AdminRpcResponse(
                requestId = "req",
                success = true,
                result = json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(Block.serializer()), blocks),
                error = null,
            )
        }
        val irohSource = IrohAdminRpcBlockSource(transport, settings)
        val apiThatThrows = object : FakeBlockApi() {
            override suspend fun listAllBlocks(
                label: String?,
                isTemplate: Boolean?,
                limit: Int?,
                offset: Int?,
            ): List<Block> {
                throw IrohAdminApiUnavailableException("Raw HTTP forbidden")
            }
        }
        val repo = BlockRepository(apiThatThrows, irohSource)
        val result = repo.listAllBlocks()
        assertEquals(2, result.size)
        assertEquals(1, transport.adminRpcCalls.size)
    }
}
