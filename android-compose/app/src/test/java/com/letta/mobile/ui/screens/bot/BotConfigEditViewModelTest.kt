package com.letta.mobile.ui.screens.bot

import androidx.lifecycle.SavedStateHandle
import com.letta.mobile.bot.config.BotConfig
import com.letta.mobile.bot.config.BotConfigStore
import com.letta.mobile.bot.config.BotScheduledJob
import com.letta.mobile.bot.skills.BotSkillRegistry
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.domain.AgentSearch
import io.mockk.coJustRun
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("unit")
class BotConfigEditViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val configStore: BotConfigStore = mockk(relaxed = true)
    private val agentRepository: AgentRepository = mockk(relaxed = true)
    private val agentSearch: AgentSearch = mockk(relaxed = true)
    private val skillRegistry: BotSkillRegistry = mockk(relaxed = true)
    private val savedStateHandle = SavedStateHandle()
    private val agentsFlow = MutableStateFlow<List<Agent>>(emptyList())

    private fun createViewModel() = BotConfigEditViewModel(
        savedStateHandle = savedStateHandle,
        configStore = configStore,
        agentRepository = agentRepository,
        agentSearch = agentSearch,
        skillRegistry = skillRegistry,
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { agentRepository.agents } returns agentsFlow
        coJustRun { configStore.saveConfig(any()) }
    }

    @Test
    fun selectAgentSetsHeartbeatAgentId() = runTest(testDispatcher) {
        val agent = Agent(id = "agent-42", name = "TestAgent")

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
    fun clearAgentSelectionClearsState() = runTest(testDispatcher) {
        val agent = Agent(id = "agent-1", name = "Agent")

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
    fun addScheduledJobIncrementsList() = runTest(testDispatcher) {
        val vm = createViewModel()

        assert(vm.scheduledJobs.isEmpty()) { "Expected empty list" }
        vm.addScheduledJob()
        assert(vm.scheduledJobs.size == 1) { "Expected 1 job" }
        vm.addScheduledJob()
        assert(vm.scheduledJobs.size == 2) { "Expected 2 jobs" }
    }

    @Test
    fun removeScheduledJobRemovesById() = runTest(testDispatcher) {
        val vm = createViewModel()

        vm.addScheduledJob()
        val firstJob = vm.scheduledJobs.first()
        vm.addScheduledJob()
        vm.removeScheduledJob(firstJob.id)
        assert(vm.scheduledJobs.size == 1) { "Expected 1 remaining job" }
    }

    @Test
    fun updateEnabledSkillsDeduplicatesAndTrims() = runTest(testDispatcher) {
        val vm = createViewModel()

        vm.updateEnabledSkills(listOf("skill-a", " skill-b ", "skill-a", ""))
        assert(vm.enabledSkills == listOf("skill-a", "skill-b")) {
            "Expected [skill-a, skill-b], got ${vm.enabledSkills}"
        }
    }

    @Test
    fun removeEnabledSkillFiltersItOut() = runTest(testDispatcher) {
        val vm = createViewModel()

        vm.updateEnabledSkills(listOf("skill-a", "skill-b", "skill-c"))
        vm.removeEnabledSkill("skill-b")
        assert(vm.enabledSkills == listOf("skill-a", "skill-c")) {
            "Expected [skill-a, skill-c], got ${vm.enabledSkills}"
        }
    }

    @Test
    fun saveRequiresHeartbeatAgentWhenEnabled() = runTest(testDispatcher) {
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
    fun saveCallsConfigStoreOnSuccess() = runTest(testDispatcher) {
        val vm = createViewModel()

        vm.displayName = "MyBot"
        vm.heartbeatEnabled = false
        var saved = false
        vm.save(onSuccess = { saved = true }, onError = {})

        assert(saved) { "Expected save to succeed" }
    }
}
