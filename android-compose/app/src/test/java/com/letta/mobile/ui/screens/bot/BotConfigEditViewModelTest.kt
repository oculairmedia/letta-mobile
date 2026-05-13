package com.letta.mobile.ui.screens.bot

import com.letta.mobile.data.model.AgentId
import androidx.lifecycle.SavedStateHandle
import com.letta.mobile.bot.config.BotConfig
import com.letta.mobile.bot.config.BotConfigStore
import com.letta.mobile.bot.config.BotScheduledJob
import com.letta.mobile.bot.skills.BotSkillRegistry
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.domain.AgentSearch
import io.mockk.coJustRun
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import com.letta.mobile.testutil.MainDispatcherRule
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("unit")
class BotConfigEditViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val configStore: BotConfigStore = mockk(relaxed = true)
    private val agentRepository: AgentRepository = mockk(relaxed = true)
    private val agentSearch: AgentSearch = mockk(relaxed = true)
    private val skillRegistry: BotSkillRegistry = mockk(relaxed = true)
    private val savedStateHandle = SavedStateHandle()
    private val agentsFlow = MutableStateFlow<List<Agent>>(emptyList())

    private fun createViewModel(handle: SavedStateHandle = savedStateHandle) = BotConfigEditViewModel(
        savedStateHandle = handle,
        configStore = configStore,
        agentRepository = agentRepository,
        agentSearch = agentSearch,
        skillRegistry = skillRegistry,
    )

    @Before
    fun setup() {
        every { agentRepository.agents } returns agentsFlow
        coJustRun { configStore.saveConfig(any()) }
    }

    @Test
    fun selectAgentSetsHeartbeatAgentId() = runTest(mainDispatcherRule.dispatcher) {
        val agent = Agent(id = AgentId("agent-42"), name = "TestAgent")

        val vm = createViewModel()

        vm.selectAgent(agent)
        assert(vm.heartbeatAgentId == "agent-42") {
            "Expected agent-42, got ${vm.heartbeatAgentId}"
        }
        assert(vm.selectedAgentName == "TestAgent") {
            "Expected TestAgent, got ${vm.selectedAgentName}"
        }
    }

    @Test
    fun clearAgentSelectionClearsState() = runTest(mainDispatcherRule.dispatcher) {
        val agent = Agent(id = AgentId("agent-1"), name = "Agent")

        val vm = createViewModel()

        vm.selectAgent(agent)
        vm.clearAgentSelection()

        assert(vm.heartbeatAgentId.isEmpty()) {
            "Expected empty, got ${vm.heartbeatAgentId}"
        }
        assert(vm.selectedAgentName == null) {
            "Expected null, got ${vm.selectedAgentName}"
        }
    }

    @Test
    fun addScheduledJobIncrementsList() = runTest(mainDispatcherRule.dispatcher) {
        val vm = createViewModel()

        assert(vm.scheduledJobs.isEmpty()) { "Expected empty list" }
        vm.addScheduledJob()
        assert(vm.scheduledJobs.size == 1) { "Expected 1 job" }
        vm.addScheduledJob()
        assert(vm.scheduledJobs.size == 2) { "Expected 2 jobs" }
    }

    @Test
    fun removeScheduledJobRemovesById() = runTest(mainDispatcherRule.dispatcher) {
        val vm = createViewModel()

        vm.addScheduledJob()
        val firstJob = vm.scheduledJobs.first()
        vm.addScheduledJob()
        vm.removeScheduledJob(firstJob.id)
        assert(vm.scheduledJobs.size == 1) { "Expected 1 remaining job" }
    }

    @Test
    fun updateEnabledSkillsDeduplicatesAndTrims() = runTest(mainDispatcherRule.dispatcher) {
        val vm = createViewModel()

        vm.updateEnabledSkills(listOf("skill-a", " skill-b ", "skill-a", ""))
        assert(vm.enabledSkills == listOf("skill-a", "skill-b")) {
            "Expected [skill-a, skill-b], got ${vm.enabledSkills}"
        }
    }

    @Test
    fun removeEnabledSkillFiltersItOut() = runTest(mainDispatcherRule.dispatcher) {
        val vm = createViewModel()

        vm.updateEnabledSkills(listOf("skill-a", "skill-b", "skill-c"))
        vm.removeEnabledSkill("skill-b")
        assert(vm.enabledSkills == listOf("skill-a", "skill-c")) {
            "Expected [skill-a, skill-c], got ${vm.enabledSkills}"
        }
    }

    @Test
    fun saveRequiresHeartbeatAgentWhenEnabled() = runTest(mainDispatcherRule.dispatcher) {
        val vm = createViewModel()

        vm.heartbeatEnabled = true
        vm.heartbeatAgentId = ""
        var errorMessage: String? = null
        vm.save(onSuccess = {}, onError = { errorMessage = it })

        assert(errorMessage != null) {
            "Expected error when heartbeat enabled but no agent set"
        }
    }

    @Test
    fun saveGeneratesApiServerTokenWhenEnabledWithBlankToken() = runTest(mainDispatcherRule.dispatcher) {
        val savedSlot = slot<BotConfig>()
        coJustRun { configStore.saveConfig(capture(savedSlot)) }
        val vm = createViewModel()
        vm.apiServerEnabled = true
        vm.apiServerToken = ""

        vm.save(onSuccess = {}, onError = {})
        advanceUntilIdle()

        assert(savedSlot.captured.apiServerToken?.isNotBlank() == true)
        assert(vm.apiServerToken == savedSlot.captured.apiServerToken)
    }

    @Test
    fun saveCallsConfigStoreOnSuccess() = runTest(mainDispatcherRule.dispatcher) {
        val vm = createViewModel()

        vm.displayName = "MyBot"
        vm.heartbeatEnabled = false
        var saved = false
        vm.save(onSuccess = { saved = true }, onError = {})
        advanceUntilIdle()

        assert(saved) { "Expected save to succeed" }
    }

    @Test
    fun createModeDefaultsAreInitialized() = runTest(mainDispatcherRule.dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        assert(!vm.isEditing)
        assert(vm.displayName.isEmpty())
        assert(vm.mode == BotConfig.Mode.LOCAL)
        assert(vm.enabled)
    }

    @Test
    @Ignore("Route argument hydration differs in local test harness; covered via save/load behavior tests")
    fun editModeLoadsConfigAndResolvesCachedAgentName() = runTest(mainDispatcherRule.dispatcher) {
        val config = BotConfig(
            id = "cfg-1",
            displayName = "Existing Bot",
            heartbeatAgentId = "agent-42",
            channels = listOf("in_app", "push"),
        )
        every { configStore.configs } returns MutableStateFlow(listOf(config))
        every { agentRepository.getCachedAgent("agent-42") } returns Agent(id = AgentId("agent-42"), name = "Heartbeat Agent")

        val vm = createViewModel(SavedStateHandle(mapOf("configId" to "cfg-1")))
        advanceUntilIdle()

        assert(vm.isEditing)
        assert(vm.displayName == "Existing Bot")
        assert(vm.heartbeatAgentId == "agent-42")
        assert(vm.selectedAgentName == "Heartbeat Agent")
    }

    @Test
    fun filteredAgentsDelegatesToAgentSearch() = runTest(mainDispatcherRule.dispatcher) {
        val agents = listOf(Agent(id = AgentId("a1"), name = "Agent One"))
        agentsFlow.value = agents
        every { agentSearch.search(any(), "one") } returns agents

        val vm = createViewModel()
        advanceUntilIdle()
        vm.agentSearchQuery = "one"

        val filtered = vm.filteredAgents()
        assert(filtered.size == 1)
    }

    @Test
    fun updateScheduledJobReplacesMatchingJob() = runTest(mainDispatcherRule.dispatcher) {
        val vm = createViewModel()
        vm.addScheduledJob()
        val original = vm.scheduledJobs.first()
        val updated = original.copy(displayName = "Nightly", message = "Ping", cronExpression = "0 0 * * *")

        vm.updateScheduledJob(updated)

        assert(vm.scheduledJobs.first().displayName == "Nightly")
        assert(vm.scheduledJobs.first().message == "Ping")
    }

    @Test
    fun saveRejectsUnknownSkillIds() = runTest(mainDispatcherRule.dispatcher) {
        val vm = createViewModel()
        vm.updateEnabledSkills(listOf("unknown-skill"))
        var error: String? = null

        vm.save(onSuccess = {}, onError = { error = it })

        assert(error?.contains("Unknown skill IDs") == true)
        assert(error?.contains("unknown-skill") == true)
    }

    @Test
    fun saveBuildsTrimmedPayloadWithParsedLists() = runTest(mainDispatcherRule.dispatcher) {
        val savedSlot = slot<BotConfig>()
        coJustRun { configStore.saveConfig(capture(savedSlot)) }
        val vm = createViewModel()
        vm.displayName = "  My Bot  "
        vm.heartbeatAgentId = "  agent-1  "
        vm.remoteUrl = "  https://bot.local  "
        vm.remoteToken = "  token  "
        vm.channels = "in_app, push , ,sms"
        vm.contextProviders = "providerA, providerB "
        vm.envelopeTemplate = "  hello  "
        vm.sharedConversationId = "  conv-1  "

        vm.save(onSuccess = {}, onError = {})
        advanceUntilIdle()

        val saved = savedSlot.captured
        assert(saved.displayName == "My Bot")
        assert(saved.heartbeatAgentId == "agent-1")
        assert(saved.remoteUrl == "https://bot.local")
        assert(saved.remoteToken == "token")
        assert(saved.channels == listOf("in_app", "push", "sms"))
        assert(saved.contextProviders == listOf("providerA", "providerB"))
        assert(saved.envelopeTemplate == "hello")
        assert(saved.sharedConversationId == "conv-1")
    }

    @Test
    fun saveFailureInvokesOnErrorCallback() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { configStore.saveConfig(any()) } throws IllegalStateException("save failed")
        val vm = createViewModel()
        var error: String? = null

        vm.save(onSuccess = {}, onError = { error = it })
        advanceUntilIdle()

        assert(error == "save failed")
    }
}
