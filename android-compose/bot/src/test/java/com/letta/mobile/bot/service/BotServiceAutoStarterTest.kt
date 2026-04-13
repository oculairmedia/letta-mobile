package com.letta.mobile.bot.service

import com.letta.mobile.bot.config.BotConfig
import io.kotest.core.spec.style.WordSpec
import io.kotest.matchers.shouldBe

class BotServiceAutoStarterTest : WordSpec({
    "shouldAutoStartBotService" should {
        "return true when an enabled bot is marked auto start" {
            shouldAutoStartBotService(
                listOf(
                    BotConfig(id = "bot-1", agentId = "agent-1", enabled = true, autoStart = true),
                )
            ) shouldBe true
        }

        "return false when auto start configs are disabled" {
            shouldAutoStartBotService(
                listOf(
                    BotConfig(id = "bot-1", agentId = "agent-1", enabled = false, autoStart = true),
                    BotConfig(id = "bot-2", agentId = "agent-2", enabled = true, autoStart = false),
                )
            ) shouldBe false
        }
    }
})
