package com.letta.mobile.ui.screens.dashboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.Step
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.AllConversationsRepository
import com.letta.mobile.data.repository.MessageRepository
import com.letta.mobile.data.repository.SettingsRepository
import com.letta.mobile.data.repository.StepRepository
import com.letta.mobile.data.repository.ToolRepository
import com.letta.mobile.data.repository.api.IBlockRepository
import com.letta.mobile.testutil.FakeStepApi
import com.letta.mobile.testutil.TestData
import com.letta.mobile.util.EncryptedPrefsHelper
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var agentRepository: AgentRepository
    private lateinit var conversationsRepository: AllConversationsRepository
    private lateinit var toolRepository: ToolRepository
    private lateinit var blockRepository: IBlockRepository
    private lateinit var messageRepository: MessageRepository
    private lateinit var stepRepository: StepRepository
    private lateinit var fakeStepApi: FakeStepApi

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sharedPreferences = context.getSharedPreferences("dashboard-view-model-test", Context.MODE_PRIVATE)
        sharedPreferences.edit().clear().commit()
        mockkObject(EncryptedPrefsHelper)
        every { EncryptedPrefsHelper.getEncryptedPrefs(any()) } returns sharedPreferences

        settingsRepository = SettingsRepository(context)

        agentRepository = mockk(relaxed = true)
        every {
            agentRepository.agents
        } returns MutableStateFlow(
            listOf(
                TestData.agent(id = "agent-1", name = "Agent One"),
                TestData.agent(id = "agent-2", name = "Agent Two"),
            )
        )
        coEvery { agentRepository.refreshAgents() } returns Unit
        every { agentRepository.getCachedAgent(any()) } answers {
            agentRepository.agents.value.firstOrNull { it.id == firstArg() }
        }
        every { agentRepository.getAgent(any()) } answers {
            flowOf(agentRepository.agents.value.first { it.id == firstArg() })
        }

        conversationsRepository = mockk(relaxed = true)
        every {
            conversationsRepository.conversations
        } returns MutableStateFlow(listOf(TestData.conversation(id = "conv-1", agentId = "agent-1")))
        coEvery { conversationsRepository.refresh() } returns Unit

        toolRepository = mockk(relaxed = true)
        every { toolRepository.getTools() } returns flowOf(
            listOf(
                TestData.tool(id = "tool-1"),
                TestData.tool(id = "tool-2"),
                TestData.tool(id = "tool-3"),
            )
        )
        coEvery { toolRepository.refreshTools() } returns Unit

        blockRepository = mockk(relaxed = true)
        coEvery { blockRepository.listAllBlocks() } returns listOf(
            TestData.block(id = "block-1"),
            TestData.block(id = "block-2", label = "human"),
        )

        messageRepository = mockk(relaxed = true)
        coEvery { messageRepository.searchMessages(any()) } returns emptyList()

        fakeStepApi = FakeStepApi()
        stepRepository = StepRepository(fakeStepApi)
    }

    @After
    fun tearDown() {
        unmockkObject(EncryptedPrefsHelper)
        Dispatchers.resetMain()
    }

    @Test
    fun `loadProgressively populates homepage usage analytics`() = runTest {
        fakeStepApi.steps.addAll(
            listOf(
                sampleStep(id = "step-1", model = "gpt-4.1", totalTokens = 1200),
                sampleStep(id = "step-2", model = "gpt-4.1", totalTokens = 300),
                sampleStep(id = "step-3", model = "claude-3.7", totalTokens = 900),
            )
        )

        val viewModel = DashboardViewModel(
            agentRepository = agentRepository,
            allConversationsRepository = conversationsRepository,
            toolRepository = toolRepository,
            blockRepository = blockRepository,
            settingsRepository = settingsRepository,
            messageRepository = messageRepository,
            stepRepository = stepRepository,
        )

        val state = viewModel.uiState.value
        assertEquals(2, state.agentCount)
        assertEquals(1, state.conversationCount)
        assertEquals(3, state.toolCount)
        assertEquals(2, state.blockCount)
        assertFalse(state.isUsageLoading)
        assertEquals(2400, state.usageSummary?.totalTokens)
        assertEquals(100, state.usageSummary?.averageTokensPerHour)
        assertEquals(listOf("gpt-4.1", "claude-3.7"), state.usageSummary?.modelUsage?.map { it.model })
        assertEquals(1500, state.usageSummary?.modelUsage?.first()?.totalTokens)
    }

    @Test
    fun `loadProgressively leaves usage empty when analytics fetch fails`() = runTest {
        fakeStepApi.shouldFail = true

        val viewModel = DashboardViewModel(
            agentRepository = agentRepository,
            allConversationsRepository = conversationsRepository,
            toolRepository = toolRepository,
            blockRepository = blockRepository,
            settingsRepository = settingsRepository,
            messageRepository = messageRepository,
            stepRepository = stepRepository,
        )

        val state = viewModel.uiState.value
        assertFalse(state.isUsageLoading)
        assertNull(state.usageSummary)
        assertTrue(state.agentCount != null)
    }

    private fun sampleStep(id: String, model: String, totalTokens: Int) = Step(
        id = id,
        agentId = "agent-1",
        model = model,
        totalTokens = totalTokens,
    )
}
