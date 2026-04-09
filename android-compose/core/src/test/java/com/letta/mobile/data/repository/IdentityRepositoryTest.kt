package com.letta.mobile.data.repository

import com.letta.mobile.data.model.Identity
import com.letta.mobile.data.model.IdentityCreateParams
import com.letta.mobile.data.model.IdentityUpdateParams
import com.letta.mobile.data.model.IdentityUpsertParams
import com.letta.mobile.testutil.FakeIdentityApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IdentityRepositoryTest {

    private lateinit var fakeApi: FakeIdentityApi
    private lateinit var repository: IdentityRepository

    @Before
    fun setup() {
        fakeApi = FakeIdentityApi()
        repository = IdentityRepository(fakeApi)
    }

    @Test
    fun `refreshIdentities updates state flow`() = runTest {
        fakeApi.identities.add(sampleIdentity("identity-1"))

        repository.refreshIdentities()

        assertEquals(1, repository.identities.first().size)
    }

    @Test
    fun `createIdentity upserts cache`() = runTest {
        val created = repository.createIdentity(
            IdentityCreateParams(identifierKey = "user-1", name = "User One", identityType = "user")
        )

        assertEquals("user-1", created.identifierKey)
        assertEquals(1, repository.identities.first().size)
    }

    @Test
    fun `updateIdentity updates cached identity`() = runTest {
        fakeApi.identities.add(sampleIdentity("identity-1"))
        repository.refreshIdentities()

        val updated = repository.updateIdentity("identity-1", IdentityUpdateParams(name = "Updated"))

        assertEquals("Updated", updated.name)
        assertEquals("Updated", repository.identities.first().first().name)
    }

    @Test
    fun `deleteIdentity removes cached identity`() = runTest {
        fakeApi.identities.add(sampleIdentity("identity-1"))
        repository.refreshIdentities()

        repository.deleteIdentity("identity-1")

        assertTrue(repository.identities.first().isEmpty())
    }

    @Test
    fun `attachIdentity delegates to api`() = runTest {
        repository.attachIdentity("agent-1", "identity-1")
        assertTrue(fakeApi.calls.contains("attachIdentity:agent-1:identity-1"))
    }

    @Test
    fun `detachIdentity delegates to api`() = runTest {
        repository.detachIdentity("agent-1", "identity-1")
        assertTrue(fakeApi.calls.contains("detachIdentity:agent-1:identity-1"))
    }

    @Test
    fun `upsertIdentity stores returned identity`() = runTest {
        val identity = repository.upsertIdentity(
            IdentityUpsertParams(identifierKey = "user-1", name = "User One", identityType = "user")
        )

        assertEquals("user-1", identity.identifierKey)
        assertEquals(1, repository.identities.first().size)
    }

    @Test
    fun `getIdentity retrieves identity by id`() = runTest {
        fakeApi.identities.add(sampleIdentity("identity-1"))

        val identity = repository.getIdentity("identity-1")

        assertEquals("identity-1", identity.id)
    }

    private fun sampleIdentity(id: String) = Identity(
        id = id,
        identifierKey = "user-1",
        name = "User One",
        identityType = "user",
    )
}
