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
import org.junit.jupiter.api.Tag

@Tag("unit")
class InternalBotClientTest : WordSpec({
    "getStatus" should {
        "include gateway sessions and active config metadata" {
            val gateway = mockk<BotGateway>()
            val configStore = mockk<BotConfigStore>()
            val session = mockk<BotSession>()

            every { gateway.status } returns MutableStateFlow(com.letta.mobile.bot.core.GatewayStatus.RUNNING)
            // letta-mobile-w2hx.4: gateway sessions are keyed on
            // `config.id`, not on the bound agent ID.
            every { gateway.sessions } returns MutableStateFlow(mapOf("local" to session))
            every { session.displayName } returns "Primary agent"
            every { session.status } returns MutableStateFlow(BotStatus.RUNNING)
            coEvery { configStore.getAll() } returns listOf(
                BotConfig(
                    id = "local",
                    heartbeatAgentId = "agent-1",
                    heartbeatEnabled = true,
                    enabled = true,
                    mode = BotConfig.Mode.LOCAL,
                ),
                BotConfig(
                    id = "remote",
                    heartbeatAgentId = "agent-2",
                    heartbeatEnabled = true,
                    enabled = true,
                    mode = BotConfig.Mode.REMOTE,
                    serverProfileId = "profile-a",
                ),
            )

            val status = runBlocking { InternalBotClient(gateway, configStore).getStatus() }

            status.status shouldBe "running"
            // .agents now reports configured heartbeat targets, not session keys.
            status.agents shouldContainExactly listOf("agent-1", "agent-2")
            status.sessionCount shouldBe 1
            status.agentDetails shouldContainExactly listOf(
                BotAgentInfo(
                    id = "local",
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
