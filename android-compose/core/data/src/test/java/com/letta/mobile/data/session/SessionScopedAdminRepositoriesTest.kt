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
    fun `admin repository proxies switch caches to rebuilt graph`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fakeFolderApi = FakeFolderApi().apply {
            folders = mutableListOf(Folder(id = FolderId("folder-a"), name = "Backend A Folder"))
        }
        val fakeGroupApi = FakeGroupApi().apply {
            groups = mutableListOf(sampleGroup("group-a", "Backend A Group"))
        }
        val fakeIdentityApi = FakeIdentityApi().apply {
            identities = mutableListOf(sampleIdentity("identity-a", "Backend A Identity"))
        }
        val fakeProviderApi = FakeProviderApi().apply {
            providers = mutableListOf(sampleProvider("provider-a", "Backend A Provider"))
        }
        val settingsRepository = FakeSettingsRepository(initialActiveConfig = config("backend-a"))
        val sessionManager = SessionManager(
            settingsRepository = settingsRepository,
            sessionGraphFactory = SessionGraphFactory(
                FakeAgentApi(),
                FakeAgentDao(),
                FakeConversationApi(),
                FakeConversationDao(),
                FakeArchiveApi(),
                fakeFolderApi,
                fakeGroupApi,
                fakeIdentityApi,
                fakeLettaApiClient(),
                FakeMcpServerApi(),
                FakeModelApi(),
                FakePassageApi(),
                FakeProjectApi(),
                FakeProjectWorkApi(),
                FakeRunApi(),
                FakeJobApi(),
                fakeProviderApi,
                FakeScheduleApi(),
                FakeStepApi(),
                FakeToolApi(),
                appContext = mockk(relaxed = true),
            ),
            managerScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        val folderProxy = SessionScopedFolderRepository(
            sessionManager = sessionManager,
            proxyScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        val groupProxy = SessionScopedGroupRepository(
            sessionManager = sessionManager,
            proxyScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        val identityProxy = SessionScopedIdentityRepository(
            sessionManager = sessionManager,
            proxyScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        val providerProxy = SessionScopedProviderRepository(
            sessionManager = sessionManager,
            proxyScope = CoroutineScope(SupervisorJob() + dispatcher),
        )

        folderProxy.refreshFolders()
        groupProxy.refreshGroups()
        identityProxy.refreshIdentities()
        providerProxy.refreshProviders()
        advanceUntilIdle()
        assertEquals(listOf(FolderId("folder-a")), folderProxy.folders.value.map { it.id })
        assertEquals(listOf(GroupId("group-a")), groupProxy.groups.value.map { it.id })
        assertEquals(listOf(IdentityId("identity-a")), identityProxy.identities.value.map { it.id })
        assertEquals(listOf(ProviderId("provider-a")), providerProxy.providers.value.map { it.id })

        fakeFolderApi.folders = mutableListOf(Folder(id = FolderId("folder-b"), name = "Backend B Folder"))
        fakeGroupApi.groups = mutableListOf(sampleGroup("group-b", "Backend B Group"))
        fakeIdentityApi.identities = mutableListOf(sampleIdentity("identity-b", "Backend B Identity"))
        fakeProviderApi.providers = mutableListOf(sampleProvider("provider-b", "Backend B Provider"))
        settingsRepository.activeConfigState.value = config("backend-b")
        advanceUntilIdle()

        assertEquals(emptyList<FolderId>(), folderProxy.folders.value.map { it.id })
        assertEquals(emptyList<GroupId>(), groupProxy.groups.value.map { it.id })
        assertEquals(emptyList<IdentityId>(), identityProxy.identities.value.map { it.id })
        assertEquals(emptyList<ProviderId>(), providerProxy.providers.value.map { it.id })

        folderProxy.refreshFolders()
        groupProxy.refreshGroups()
        identityProxy.refreshIdentities()
        providerProxy.refreshProviders()
        advanceUntilIdle()

        assertEquals(listOf(FolderId("folder-b")), folderProxy.folders.value.map { it.id })
        assertEquals(listOf(GroupId("group-b")), groupProxy.groups.value.map { it.id })
        assertEquals(listOf(IdentityId("identity-b")), identityProxy.identities.value.map { it.id })
        assertEquals(listOf(ProviderId("provider-b")), providerProxy.providers.value.map { it.id })
    }

    private fun fakeLettaApiClient(): LettaApiClient = mockk(relaxed = true)

    private fun config(id: String, serverUrl: String = "https://$id.example.test"): LettaConfig = sessionTestConfig(id, serverUrl)

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
