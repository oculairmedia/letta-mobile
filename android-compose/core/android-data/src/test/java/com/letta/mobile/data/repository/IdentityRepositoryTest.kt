package com.letta.mobile.data.repository

import com.letta.mobile.data.api.IrohAdminApiUnavailableException
import com.letta.mobile.data.model.Identity
import com.letta.mobile.data.model.IdentityId
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.testutil.FakeChannelTransport
import com.letta.mobile.testutil.FakeIdentityApi
import com.letta.mobile.testutil.FakeSettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class IdentityRepositoryTest {

    private lateinit var fakeApi: FakeIdentityApi
    private lateinit var repository: IdentityRepository

    @Before
    fun setup() {
        fakeApi = FakeIdentityApi()
        repository = IdentityRepository(fakeApi)
    }

    @Test
    fun `refreshIdentities calls API`() = runTest {
        fakeApi.identities.add(Identity(
            id = IdentityId("i1"),
            identifierKey = "user@example.com",
            name = "Test User",
            identityType = "user",
        ))
        repository.refreshIdentities()
        assertEquals(1, fakeApi.calls.size)
    }

    @Test
    fun `getIdentity returns correct identity`() = runTest {
        val testIdentity = Identity(
            id = IdentityId("i1"),
            identifierKey = "user@example.com",
            name = "Test User",
            identityType = "user",
        )
        fakeApi.identities.add(testIdentity)
        val result = repository.getIdentity(IdentityId("i1"))
        assertEquals(IdentityId("i1"), result.id)
        assertEquals("Test User", result.name)
    }

    // ─── Iroh Purity Tests (letta-mobile client batch) ────────────────────────

    @Test(expected = IrohAdminApiUnavailableException::class)
    fun `refreshIdentities in iroh mode without source throws IrohAdminApiUnavailableException`() = runTest {
        val settings = FakeSettingsRepository(
            initialActiveConfig = LettaConfig(
                id = "test",
                mode = LettaConfig.Mode.SELF_HOSTED,
                serverUrl = "iroh://test-node",
                accessToken = "token",
            )
        )
        val apiThatThrows = object : FakeIdentityApi() {
            override suspend fun listIdentities(): List<Identity> {
                throw IrohAdminApiUnavailableException("Raw HTTP forbidden in iroh:// mode")
            }
        }
        val repo = IdentityRepository(apiThatThrows)
        repo.refreshIdentities()
    }

    @Test
    fun `refreshIdentities in iroh mode routes via admin_rpc`() = runTest {
        val settings = FakeSettingsRepository(
            initialActiveConfig = LettaConfig(
                id = "test",
                mode = LettaConfig.Mode.SELF_HOSTED,
                serverUrl = "iroh://test-node",
                accessToken = "token",
            )
        )
        val transport = FakeChannelTransport()
        val testIdentities = listOf(
            Identity(
                id = IdentityId("i1"),
                identifierKey = "user1@example.com",
                name = "User 1",
                identityType = "user",
            ),
            Identity(
                id = IdentityId("i2"),
                identifierKey = "user2@example.com",
                name = "User 2",
                identityType = "user",
            ),
        )
        transport.adminRpcHandler = { method, path, body ->
            assertEquals("identity.list", method)
            assertEquals("/v1/identities", path)
            val json = Json { ignoreUnknownKeys = true }
            AppServerInboundFrame.AdminRpcResponse(
                requestId = "req",
                success = true,
                result = json.encodeToJsonElement(ListSerializer(Identity.serializer()), testIdentities),
                error = null,
            )
        }
        val irohSource = IrohAdminRpcIdentitySource(transport, settings)
        val apiThatThrows = object : FakeIdentityApi() {
            override suspend fun listIdentities(): List<Identity> {
                throw IrohAdminApiUnavailableException("Raw HTTP forbidden")
            }
        }
        val repo = IdentityRepository(apiThatThrows, irohSource)
        repo.refreshIdentities()
        assertEquals(2, repo.identities.value.size)
        assertEquals(1, transport.adminRpcCalls.size)
    }

    @Test
    fun `getIdentity in iroh mode routes via admin_rpc`() = runTest {
        val settings = FakeSettingsRepository(
            initialActiveConfig = LettaConfig(
                id = "test",
                mode = LettaConfig.Mode.SELF_HOSTED,
                serverUrl = "iroh://test-node",
                accessToken = "token",
            )
        )
        val transport = FakeChannelTransport()
        val testIdentity = Identity(
            id = IdentityId("i1"),
            identifierKey = "user@example.com",
            name = "Test User",
            identityType = "user",
        )
        transport.adminRpcHandler = { method, path, body ->
            assertEquals("identity.get", method)
            assertEquals("/v1/identities/i1", path)
            val json = Json { ignoreUnknownKeys = true }
            AppServerInboundFrame.AdminRpcResponse(
                requestId = "req",
                success = true,
                result = json.encodeToJsonElement(Identity.serializer(), testIdentity),
                error = null,
            )
        }
        val irohSource = IrohAdminRpcIdentitySource(transport, settings)
        val apiThatThrows = object : FakeIdentityApi() {
            override suspend fun retrieveIdentity(identityId: String): Identity {
                throw IrohAdminApiUnavailableException("Raw HTTP forbidden")
            }
        }
        val repo = IdentityRepository(apiThatThrows, irohSource)
        val result = repo.getIdentity(IdentityId("i1"))
        assertEquals(IdentityId("i1"), result.id)
        assertEquals(1, transport.adminRpcCalls.size)
    }
}
