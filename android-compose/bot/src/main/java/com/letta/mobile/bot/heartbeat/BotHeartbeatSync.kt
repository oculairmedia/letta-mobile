package com.letta.mobile.bot.heartbeat

import androidx.work.ListenableWorker
import com.letta.mobile.bot.channel.ChannelMessage
import com.letta.mobile.bot.config.BotConfig
import com.letta.mobile.bot.config.BotScheduledJob
import com.letta.mobile.bot.config.BotConfigStore
import com.letta.mobile.bot.core.BotGateway
import com.letta.mobile.bot.core.GatewayStatus
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BotHeartbeatSync @Inject constructor(
    private val configStore: BotConfigStore,
    private val stateStore: BotHeartbeatStateStore,
    private val gateway: BotGateway,
) {
    suspend fun run(): ListenableWorker.Result {
        val now = System.currentTimeMillis()
        val configs = configStore.getAll().filter { it.enabled }
        val heartbeatConfigs = configs.filter { it.heartbeatEnabled }
        val scheduledJobs = collectDueScheduledJobs(configs, now)
        if (heartbeatConfigs.isEmpty() && scheduledJobs.isEmpty()) {
            return ListenableWorker.Result.success()
        }

        val dueConfigs = heartbeatConfigs.filter { config ->
            shouldRunHeartbeat(config, stateStore.getLastRunAt(config.id), now)
        }
        if (dueConfigs.isEmpty() && scheduledJobs.isEmpty()) {
            return ListenableWorker.Result.success()
        }

        val gatewayWasRunning = gateway.status.value == GatewayStatus.RUNNING && gateway.sessions.value.isNotEmpty()
        if (!gatewayWasRunning) {
            gateway.start(configs)
        }

        val failures = mutableListOf<Throwable>()
        try {
            dueConfigs.forEach { config ->
                runCatching {
                    gateway.routeMessage(config.toHeartbeatMessage(now))
                    stateStore.setLastRunAt(config.id, now)
                }.onFailure { failures += it }
            }
            scheduledJobs.forEach { scheduledJob ->
                runCatching {
                    gateway.routeMessage(scheduledJob.toChannelMessage())
                    stateStore.setLastScheduledRunAt(
                        configId = scheduledJob.config.id,
                        jobId = scheduledJob.job.id,
                        timestampMillis = scheduledJob.runAtMillis,
                    )
                }.onFailure { failures += it }
            }
        } finally {
            if (!gatewayWasRunning) {
                gateway.stop()
            }
        }

        return if (failures.isEmpty()) {
            ListenableWorker.Result.success()
        } else {
            ListenableWorker.Result.retry()
        }
    }

    private suspend fun collectDueScheduledJobs(
        configs: List<BotConfig>,
        nowMillis: Long,
    ): List<DueScheduledJob> {
        return configs.flatMap { config ->
            config.scheduledJobs
                .filter { it.enabled }
                .mapNotNull { job ->
                    val runAtMillis = latestDueScheduledRunAt(
                        job = job,
                        lastRunAt = stateStore.getLastScheduledRunAt(config.id, job.id),
                        nowMillis = nowMillis,
                    ) ?: return@mapNotNull null
                    DueScheduledJob(config = config, job = job, runAtMillis = runAtMillis)
                }
        }
    }
}

internal fun shouldRunHeartbeat(config: BotConfig, lastRunAt: Long?, nowMillis: Long): Boolean {
    if (!config.enabled || !config.heartbeatEnabled) return false
    val intervalMillis = config.heartbeatIntervalMinutes.coerceAtLeast(15L) * 60_000L
    val previous = lastRunAt ?: return true
    return nowMillis - previous >= intervalMillis
}

internal fun latestDueScheduledRunAt(
    job: BotScheduledJob,
    lastRunAt: Long?,
    nowMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): Long? {
    if (!job.enabled) return null
    val matcher = CronExpressionMatcher.parse(job.cronExpression) ?: return null
    val now = Instant.ofEpochMilli(nowMillis).atZone(zoneId).truncatedTo(ChronoUnit.MINUTES)
    val lowerBoundMillis = maxOf(
        (lastRunAt ?: Long.MIN_VALUE) + 1,
        nowMillis - job.staleGraceMinutes.coerceAtLeast(1L) * 60_000L,
    )
    var cursor = now
    while (cursor.toInstant().toEpochMilli() >= lowerBoundMillis) {
        if (matcher.matches(cursor)) {
            return cursor.toInstant().toEpochMilli()
        }
        cursor = cursor.minusMinutes(1)
    }
    return null
}

internal fun BotConfig.toHeartbeatMessage(timestampMillis: Long): ChannelMessage = ChannelMessage(
    messageId = "heartbeat:$id:$timestampMillis",
    channelId = HEARTBEAT_CHANNEL_ID,
    chatId = "heartbeat:$id",
    senderId = HEARTBEAT_SENDER_ID,
    senderName = "Heartbeat",
    text = heartbeatMessage,
    targetAgentId = agentId,
    timestamp = timestampMillis,
    metadata = mapOf(
        "source" to "heartbeat",
        "config_id" to id,
    ),
)

internal fun DueScheduledJob.toChannelMessage(): ChannelMessage = ChannelMessage(
    messageId = "scheduled:${config.id}:${job.id}:$runAtMillis",
    channelId = SCHEDULED_JOB_CHANNEL_ID,
    chatId = "scheduled:${config.id}:${job.id}",
    senderId = SCHEDULED_JOB_SENDER_ID,
    senderName = job.displayName.ifBlank { "Scheduled Job" },
    text = job.message,
    targetAgentId = config.agentId,
    timestamp = runAtMillis,
    metadata = mapOf(
        "source" to "scheduled_job",
        "config_id" to config.id,
        "job_id" to job.id,
        "cron_expression" to job.cronExpression,
    ),
)

internal data class DueScheduledJob(
    val config: BotConfig,
    val job: BotScheduledJob,
    val runAtMillis: Long,
)

internal class CronExpressionMatcher private constructor(
    private val minutes: CronField,
    private val hours: CronField,
    private val daysOfMonth: CronField,
    private val months: CronField,
    private val daysOfWeek: CronField,
) {
    fun matches(dateTime: ZonedDateTime): Boolean {
        return minutes.matches(dateTime.minute) &&
            hours.matches(dateTime.hour) &&
            daysOfMonth.matches(dateTime.dayOfMonth) &&
            months.matches(dateTime.monthValue) &&
            daysOfWeek.matches(dateTime.dayOfWeek.value % 7)
    }

    companion object {
        fun parse(expression: String): CronExpressionMatcher? {
            val parts = expression.trim().split(Regex("\\s+"))
            if (parts.size != 5) return null
            return runCatching {
                CronExpressionMatcher(
                    minutes = CronField.parse(parts[0], 0..59),
                    hours = CronField.parse(parts[1], 0..23),
                    daysOfMonth = CronField.parse(parts[2], 1..31),
                    months = CronField.parse(parts[3], 1..12),
                    daysOfWeek = CronField.parse(parts[4], 0..6),
                )
            }.getOrNull()
        }
    }
}

internal class CronField private constructor(
    private val values: Set<Int>,
) {
    fun matches(value: Int): Boolean = value in values

    companion object {
        fun parse(part: String, validRange: IntRange): CronField {
            val values = buildSet {
                part.split(',').forEach { token ->
                    addAll(parseToken(token.trim(), validRange))
                }
            }
            require(values.isNotEmpty())
            return CronField(values)
        }

        private fun parseToken(token: String, validRange: IntRange): Set<Int> {
            require(token.isNotBlank())
            if (token == "*") return validRange.toSet()

            val stepParts = token.split('/')
            require(stepParts.size <= 2)
            val base = stepParts[0]
            val step = stepParts.getOrNull(1)?.toInt()?.also { require(it > 0) } ?: 1

            val baseValues = when {
                base == "*" -> validRange.toList()
                '-' in base -> {
                    val (start, end) = base.split('-', limit = 2)
                    val range = start.toInt()..end.toInt()
                    require(range.first in validRange && range.last in validRange)
                    range.toList()
                }
                else -> listOf(base.toInt())
            }

            return baseValues
                .filter { it in validRange }
                .filterIndexed { index, _ -> index % step == 0 }
                .toSet()
        }
    }
}

private const val HEARTBEAT_CHANNEL_ID = "heartbeat"
private const val HEARTBEAT_SENDER_ID = "system:heartbeat"
private const val SCHEDULED_JOB_CHANNEL_ID = "schedule"
private const val SCHEDULED_JOB_SENDER_ID = "system:schedule"
