package com.letta.mobile.bot.heartbeat

import com.letta.mobile.bot.config.BotConfig
import com.letta.mobile.bot.config.BotScheduledJob
import io.kotest.core.spec.style.WordSpec
import io.kotest.matchers.maps.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.jupiter.api.Tag

@Tag("unit")
class BotHeartbeatSyncTest : WordSpec({
    "shouldRunHeartbeat" should {
        "run when enabled and no previous execution exists" {
            shouldRunHeartbeat(
                config = BotConfig(id = "bot-1", heartbeatAgentId = "agent-1", enabled = true, heartbeatEnabled = true),
                lastRunAt = null,
                nowMillis = 1_000L,
            ) shouldBe true
        }

        "wait until the configured interval has elapsed" {
            val config = BotConfig(
                id = "bot-1",
                heartbeatAgentId = "agent-1",
                enabled = true,
                heartbeatEnabled = true,
                heartbeatIntervalMinutes = 30,
            )
            shouldRunHeartbeat(config, lastRunAt = 0L, nowMillis = 10 * 60_000L) shouldBe false
            shouldRunHeartbeat(config, lastRunAt = 0L, nowMillis = 30 * 60_000L) shouldBe true
        }

        "ignore disabled or heartbeat-disabled configs" {
            shouldRunHeartbeat(
                BotConfig(id = "bot-1", heartbeatAgentId = "agent-1", enabled = false, heartbeatEnabled = true),
                lastRunAt = null,
                nowMillis = 1_000L,
            ) shouldBe false
            shouldRunHeartbeat(
                BotConfig(id = "bot-1", heartbeatAgentId = "agent-1", enabled = true, heartbeatEnabled = false),
                lastRunAt = null,
                nowMillis = 1_000L,
            ) shouldBe false
        }
    }

    "toHeartbeatMessage" should {
        "preserve the configured heartbeat prompt and target agent" {
            val config = BotConfig(
                id = "bot-1",
                heartbeatAgentId = "agent-1",
                heartbeatEnabled = true,
                heartbeatMessage = "Review notifications and surface anything important.",
            )

            val message = config.toHeartbeatMessage(timestampMillis = 1234L)

            message.channelId shouldBe "heartbeat"
            message.chatId shouldBe "heartbeat:bot-1"
            message.senderId shouldBe "system:heartbeat"
            message.targetAgentId shouldBe "agent-1"
            message.text shouldBe "Review notifications and surface anything important."
            message.metadata shouldContain ("source" to "heartbeat")
        }
    }

    "latestDueScheduledRunAt" should {
        "return the latest due minute that matches the cron expression" {
            val now = ZonedDateTime.of(2026, 4, 13, 9, 5, 0, 0, ZoneId.of("UTC"))
            val job = BotScheduledJob(
                id = "morning-briefing",
                message = "Prepare my morning briefing.",
                cronExpression = "0 9 * * *",
            )

            latestDueScheduledRunAt(
                job = job,
                lastRunAt = null,
                nowMillis = now.toInstant().toEpochMilli(),
                zoneId = ZoneId.of("UTC"),
            ) shouldBe ZonedDateTime.of(2026, 4, 13, 9, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        }

        "skip jobs that already ran for the matching minute" {
            val zone = ZoneId.of("UTC")
            val dueTime = ZonedDateTime.of(2026, 4, 13, 9, 0, 0, 0, zone)
            val now = dueTime.plusMinutes(2)
            val job = BotScheduledJob(
                id = "morning-briefing",
                message = "Prepare my morning briefing.",
                cronExpression = "0 9 * * *",
            )

            latestDueScheduledRunAt(
                job = job,
                lastRunAt = dueTime.toInstant().toEpochMilli(),
                nowMillis = now.toInstant().toEpochMilli(),
                zoneId = zone,
            ).shouldBeNull()
        }

        "respect stale grace windows instead of replaying old missed jobs" {
            val zone = ZoneId.of("UTC")
            val now = ZonedDateTime.of(2026, 4, 13, 12, 0, 0, 0, zone)
            val job = BotScheduledJob(
                id = "briefing",
                message = "Prepare my briefing.",
                cronExpression = "0 9 * * *",
                staleGraceMinutes = 30,
            )

            latestDueScheduledRunAt(
                job = job,
                lastRunAt = null,
                nowMillis = now.toInstant().toEpochMilli(),
                zoneId = zone,
            ).shouldBeNull()
        }

        "return null for invalid cron expressions" {
            val now = ZonedDateTime.of(2026, 4, 13, 9, 5, 0, 0, ZoneId.of("UTC"))
            val job = BotScheduledJob(
                id = "bad-cron",
                message = "This should not run.",
                cronExpression = "not-a-cron",
            )

            latestDueScheduledRunAt(
                job = job,
                lastRunAt = null,
                nowMillis = now.toInstant().toEpochMilli(),
                zoneId = ZoneId.of("UTC"),
            ).shouldBeNull()
        }
    }

    "toChannelMessage" should {
        "emit scheduled jobs through the shared bot message format" {
            val scheduledJob = DueScheduledJob(
                config = BotConfig(id = "bot-1", heartbeatAgentId = "agent-1"),
                job = BotScheduledJob(
                    id = "briefing",
                    displayName = "Morning briefing",
                    message = "Prepare my morning briefing.",
                    cronExpression = "0 9 * * *",
                ),
                runAtMillis = 1234L,
            )

            val message = scheduledJob.toChannelMessage()

            message.channelId shouldBe "schedule"
            message.chatId shouldBe "scheduled:bot-1:briefing"
            message.senderId shouldBe "system:schedule"
            message.senderName shouldBe "Morning briefing"
            message.targetAgentId shouldBe "agent-1"
            message.text shouldBe "Prepare my morning briefing."
            message.metadata shouldContain ("source" to "scheduled_job")
            message.metadata shouldContain ("job_id" to "briefing")
        }
    }
})
