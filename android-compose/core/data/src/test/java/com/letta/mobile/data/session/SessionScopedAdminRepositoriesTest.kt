package com.letta.mobile.data.session

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Folder
import com.letta.mobile.data.model.FolderId
import com.letta.mobile.data.model.Group
import com.letta.mobile.data.model.GroupId
import com.letta.mobile.data.model.Identity
import com.letta.mobile.data.model.IdentityId
import com.letta.mobile.data.model.Provider
import com.letta.mobile.data.model.ProviderId
import com.letta.mobile.testutil.FakeFolderApi
import com.letta.mobile.testutil.FakeGroupApi
import com.letta.mobile.testutil.FakeIdentityApi
import com.letta.mobile.testutil.FakeProviderApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionScopedAdminRepositoriesTest {

    @Test
    fun `folder repository proxy switches caches to rebuilt graph`() = runTest {
        val fakeFolderApi = FakeFolderApi().apply {
            folders = mutableListOf(Folder(id = FolderId("folder-a"), name = "Backend A Folder"))
        }
        assertProxySwitchesCaches(
            testScheduler,
            ProxySwitchScenario(
                setupGraph = { folderApi = fakeFolderApi },
                createProxy = { sm, scope -> SessionScopedFolderRepository(sm, scope) },
                refresh = { it.refreshFolders() },
                observeIds = { it.folders.value.map { f -> f.id } },
                mutateForBackendB = {
                    fakeFolderApi.folders = mutableListOf(Folder(id = FolderId("folder-b"), name = "Backend B Folder"))
                },
                expectedBefore = listOf(FolderId("folder-a")),
                expectedAfter = listOf(FolderId("folder-b")),
            ),
        )
    }

    @Test
    fun `group repository proxy switches caches to rebuilt graph`() = runTest {
        val fakeGroupApi = FakeGroupApi().apply {
            groups = mutableListOf(sampleGroup(GroupId("group-a"), "Backend A Group"))
        }
        assertProxySwitchesCaches(
            testScheduler,
            ProxySwitchScenario(
                setupGraph = { groupApi = fakeGroupApi },
                createProxy = { sm, scope -> SessionScopedGroupRepository(sm, scope) },
                refresh = { it.refreshGroups() },
                observeIds = { it.groups.value.map { g -> g.id } },
                mutateForBackendB = {
                    fakeGroupApi.groups = mutableListOf(sampleGroup(GroupId("group-b"), "Backend B Group"))
                },
                expectedBefore = listOf(GroupId("group-a")),
                expectedAfter = listOf(GroupId("group-b")),
                ),
        )
    }

    @Test
    fun `identity repository proxy switches caches to rebuilt graph`() = runTest {
        val fakeIdentityApi = FakeIdentityApi().apply {
            identities = mutableListOf(sampleIdentity(IdentityId("identity-a"), "Backend A Identity"))
        }
        assertProxySwitchesCaches(
            testScheduler,
            ProxySwitchScenario(
                setupGraph = { identityApi = fakeIdentityApi },
                createProxy = { sm, scope -> SessionScopedIdentityRepository(sm, scope) },
                refresh = { it.refreshIdentities() },
                observeIds = { it.identities.value.map { i -> i.id } },
                mutateForBackendB = {
                    fakeIdentityApi.identities = mutableListOf(sampleIdentity(IdentityId("identity-b"), "Backend B Identity"))
                },
                expectedBefore = listOf(IdentityId("identity-a")),
                expectedAfter = listOf(IdentityId("identity-b")),
            ),
        )
    }

    @Test
    fun `provider repository proxy switches caches to rebuilt graph`() = runTest {
        val fakeProviderApi = FakeProviderApi().apply {
            providers = mutableListOf(sampleProvider(ProviderId("provider-a"), "Backend A Provider"))
        }
        assertProxySwitchesCaches(
            testScheduler,
            ProxySwitchScenario(
                setupGraph = { providerApi = fakeProviderApi },
                createProxy = { sm, scope -> SessionScopedProviderRepository(sm, scope) },
                refresh = { it.refreshProviders() },
                observeIds = { it.providers.value.map { p -> p.id } },
                mutateForBackendB = {
                    fakeProviderApi.providers = mutableListOf(sampleProvider(ProviderId("provider-b"), "Backend B Provider"))
                },
                expectedBefore = listOf(ProviderId("provider-a")),
                expectedAfter = listOf(ProviderId("provider-b")),
            ),
        )
    }

    private fun sampleGroup(id: GroupId, description: String) = Group(
        id = id,
        managerType = "round_robin",
        description = description,
        agentIds = listOf(AgentId("agent-1")),
    )

    private fun sampleIdentity(id: IdentityId, name: String) = Identity(
        id = id,
        identifierKey = id.value,
        name = name,
        identityType = "user",
    )

    private fun sampleProvider(id: ProviderId, name: String) = Provider(
        id = id,
        name = name,
        providerType = "openai",
    )
}
