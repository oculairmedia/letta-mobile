package com.letta.mobile.data.session

import com.letta.mobile.data.api.LettaApiClient
import com.letta.mobile.data.local.AgentDao
import com.letta.mobile.data.local.AgentEntity
import com.letta.mobile.data.local.ConversationDao
import com.letta.mobile.data.local.ConversationEntity
import com.letta.mobile.data.local.ConversationRefreshEntity
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Folder
import com.letta.mobile.data.model.FolderId
import com.letta.mobile.data.model.Group
import com.letta.mobile.data.model.GroupId
import com.letta.mobile.data.model.Identity
import com.letta.mobile.data.model.IdentityId
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.Provider
import com.letta.mobile.data.model.ProviderId
import com.letta.mobile.testutil.FakeAgentApi
import com.letta.mobile.testutil.FakeArchiveApi
import com.letta.mobile.testutil.FakeConversationApi
import com.letta.mobile.testutil.FakeFolderApi
import com.letta.mobile.testutil.FakeGroupApi
import com.letta.mobile.testutil.FakeIdentityApi
import com.letta.mobile.testutil.FakeJobApi
import com.letta.mobile.testutil.FakeMcpServerApi
import com.letta.mobile.testutil.FakeModelApi
import com.letta.mobile.testutil.FakePassageApi
import com.letta.mobile.testutil.FakeProjectApi
import com.letta.mobile.testutil.FakeProjectWorkApi
import com.letta.mobile.testutil.FakeProviderApi
import com.letta.mobile.testutil.FakeRunApi
import com.letta.mobile.testutil.FakeScheduleApi
import com.letta.mobile.testutil.FakeSettingsRepository
import com.letta.mobile.testutil.FakeStepApi
import com.letta.mobile.testutil.FakeToolApi
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
            groups = mutableListOf(sampleGroup("group-a", "Backend A Group"))
        }
        assertProxySwitchesCaches(
            testScheduler,
            ProxySwitchScenario(
                setupGraph = { groupApi = fakeGroupApi },
                createProxy = { sm, scope -> SessionScopedGroupRepository(sm, scope) },
                refresh = { it.refreshGroups() },
                observeIds = { it.groups.value.map { g -> g.id } },
                mutateForBackendB = {
                    fakeGroupApi.groups = mutableListOf(sampleGroup("group-b", "Backend B Group"))
                },
                expectedBefore = listOf(GroupId("group-a")),
                expectedAfter = listOf(GroupId("group-b")),
            ),
        )
    }

    @Test
    fun `identity repository proxy switches caches to rebuilt graph`() = runTest {
        val fakeIdentityApi = FakeIdentityApi().apply {
            identities = mutableListOf(sampleIdentity("identity-a", "Backend A Identity"))
        }
        assertProxySwitchesCaches(
            testScheduler,
            ProxySwitchScenario(
                setupGraph = { identityApi = fakeIdentityApi },
                createProxy = { sm, scope -> SessionScopedIdentityRepository(sm, scope) },
                refresh = { it.refreshIdentities() },
                observeIds = { it.identities.value.map { i -> i.id } },
                mutateForBackendB = {
                    fakeIdentityApi.identities = mutableListOf(sampleIdentity("identity-b", "Backend B Identity"))
                },
                expectedBefore = listOf(IdentityId("identity-a")),
                expectedAfter = listOf(IdentityId("identity-b")),
            ),
        )
    }

    @Test
    fun `provider repository proxy switches caches to rebuilt graph`() = runTest {
        val fakeProviderApi = FakeProviderApi().apply {
            providers = mutableListOf(sampleProvider("provider-a", "Backend A Provider"))
        }
        assertProxySwitchesCaches(
            testScheduler,
            ProxySwitchScenario(
                setupGraph = { providerApi = fakeProviderApi },
                createProxy = { sm, scope -> SessionScopedProviderRepository(sm, scope) },
                refresh = { it.refreshProviders() },
                observeIds = { it.providers.value.map { p -> p.id } },
                mutateForBackendB = {
                    fakeProviderApi.providers = mutableListOf(sampleProvider("provider-b", "Backend B Provider"))
                },
                expectedBefore = listOf(ProviderId("provider-a")),
                expectedAfter = listOf(ProviderId("provider-b")),
            ),
        )
    }

    private fun sampleGroup(id: String, description: String) = Group(
        id = GroupId(id),
        managerType = "round_robin",
        description = description,
        agentIds = listOf(AgentId("agent-1")),
    )

    private fun sampleIdentity(id: String, name: String) = Identity(
        id = IdentityId(id),
        identifierKey = id,
        name = name,
        identityType = "user",
    )

    private fun sampleProvider(id: String, name: String) = Provider(
        id = ProviderId(id),
        name = name,
        providerType = "openai",
    )
}
