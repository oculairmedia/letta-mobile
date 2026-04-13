package com.letta.mobile.bot.protocol

import com.letta.mobile.bot.config.BotConfig
import com.letta.mobile.bot.config.BotConfigStore
import com.letta.mobile.bot.core.BotGateway
import com.letta.mobile.bot.core.BotSession
import com.letta.mobile.bot.core.BotStatus
import io.kotest.core.spec.style.WordSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

class InternalBotClientTest : WordSpec({
    "getStatus" should {
        "include gateway sessions and active config metadata" {
            val gateway = mockk<BotGateway>()
            val configStore = mockk<BotConfigStore>()
            val session = mockk<BotSession>()

            every { gateway.status } returns MutableStateFlow(com.letta.mobile.bot.core.GatewayStatus.RUNNING)
            every { gateway.sessions } returns MutableStateFlow(mapOf("agent-1" to session))
            every { session.displayName } returns "Primary agent"
            every { session.status } returns MutableStateFlow(BotStatus.RUNNING)
            coEvery { configStore.getAll() } returns listOf(
                BotConfig(
                    id = "local",
                    agentId = "agent-1",
                    enabled = true,
                    mode = BotConfig.Mode.LOCAL,
                ),
                BotConfig(
                    id = "remote",
                    agentId = "agent-2",
                    enabled = true,
                    mode = BotConfig.Mode.REMOTE,
                    serverProfileId = "profile-a",
                ),
            )

            val status = runBlocking { InternalBotClient(gateway, configStore).getStatus() }

            status.status shouldBe "running"
            status.agents shouldContainExactly listOf("agent-1")
            status.sessionCount shouldBe 1
            status.agentDetails shouldContainExactly listOf(
                BotAgentInfo(
                    id = "agent-1",
                    name = "Primary agent",
                    status = "running",
                )
            )
            status.activeModes shouldContainExactly listOf("local", "remote")
            status.activeProfileIds shouldContainExactly listOf("profile-a")
            status.apiPort shouldBe null
            status.authRequired shouldBe false
            status.rateLimitRequests shouldBe 0
            status.rateLimitWindowSeconds shouldBe 0
        }
    }
})
