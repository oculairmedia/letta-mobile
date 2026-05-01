package com.letta.mobile.clientmode

import com.letta.mobile.bot.config.BotConfig
import com.letta.mobile.bot.core.BotGateway
import com.letta.mobile.bot.core.BotSession
import com.letta.mobile.bot.core.GatewayStatus
import com.letta.mobile.data.repository.SettingsRepository
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClientModeControllerTest {

    @MockK
    lateinit var botGateway: BotGateway

    @MockK
    lateinit var settingsRepository: SettingsRepository

    private lateinit var controller: ClientModeController
    private val gatewayStatus = MutableStateFlow(GatewayStatus.STOPPED)
    private val gatewaySessions = MutableStateFlow<Map<String, BotSession>>(emptyMap())
    private val startedConfigs = mutableListOf<BotConfig>()

    @Before
    fun setUp() {
        MockKAnnotations.init(this, relaxUnitFun = true)
        mockkStatic("com.letta.mobile.clientmode.ClientModeRemoteAgentResolverKt")

        every { settingsRepository.observeClientModeEnabled() } returns flowOf(true)
        every { settingsRepository.observeClientModeBaseUrl() } returns flowOf("https://bot.example")
        every { settingsRepository.getClientModeApiKey() } returns "secret"

        every { botGateway.status } returns gatewayStatus
        every { botGateway.sessions } returns gatewaySessions
        every { botGateway.getSession(any()) } answers { gatewaySessions.value[firstArg()] }

        coEvery { botGateway.start(any()) } answers {
            val configs = firstArg<List<BotConfig>>()
            startedConfigs += configs
            val sessions = configs.associate { config ->
                config.agentId to mockk<BotSession>(relaxed = true) {
                    every { agentId } returns config.agentId
                }
            }
            gatewaySessions.value = sessions
            gatewayStatus.value = GatewayStatus.RUNNING
        }

        coEvery { botGateway.stop() } answers {
            gatewaySessions.value = emptyMap()
            gatewayStatus.value = GatewayStatus.STOPPED
        }

        controller = ClientModeController(botGateway, settingsRepository)
        ClientModeController::class.java.getDeclaredField("appInForeground").apply {
            isAccessible = true
            setBoolean(controller, true)
        }
    }

    @After
    fun tearDown() {
        controller.release()
        unmockkStatic("com.letta.mobile.clientmode.ClientModeRemoteAgentResolverKt")
    }

    @Test
    fun `ensureReady with route agent binds gateway to route agent without resolver fallback`() = runTest {
        coEvery { resolveClientModeRemoteAgent(any(), any()) } returns mockk(relaxed = true)

        val agentId = controller.ensureReady(routeAgentId = "route-agent")

        assertEquals("route-agent", agentId)
        assertEquals(listOf("route-agent"), startedConfigs.map { it.agentId })
        coVerify(exactly = 0) { resolveClientModeRemoteAgent(any(), any()) }
    }

    @Test
    fun `restartSession with route agent rebinding ignores previously active remote agent`() = runTest {
        coEvery { resolveClientModeRemoteAgent(any(), any()) } returns mockk(relaxed = true)

        controller.ensureReady(routeAgentId = "old-agent")
        val reboundAgentId = controller.restartSession(routeAgentId = "route-agent")

        assertEquals("route-agent", reboundAgentId)
        assertEquals(listOf("old-agent", "route-agent"), startedConfigs.map { it.agentId })
        coVerify(exactly = 0) { resolveClientModeRemoteAgent(any(), any()) }
    }

    @Test
    fun `ensureReady without route agent may resolve preferred remote agent`() = runTest {
        coEvery {
            resolveClientModeRemoteAgent(baseUrl = "https://bot.example", apiKey = "secret")
        } returns mockk {
            every { id } returns "preferred-agent"
        }

        val agentId = controller.ensureReady(routeAgentId = null)

        assertEquals("preferred-agent", agentId)
        assertEquals(listOf("preferred-agent"), startedConfigs.map { it.agentId })
        coVerify(exactly = 1) {
            resolveClientModeRemoteAgent(baseUrl = "https://bot.example", apiKey = "secret")
        }
    }
}
