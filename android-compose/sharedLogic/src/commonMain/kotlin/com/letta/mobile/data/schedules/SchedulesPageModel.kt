package com.letta.mobile.data.schedules

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.model.ScheduleCreateParams
import com.letta.mobile.data.model.ScheduleDefinition
import com.letta.mobile.data.model.ScheduleMessage
import com.letta.mobile.data.model.ScheduledMessage
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlin.time.Instant

/**
 * letta-mobile-ckmub.3.16 (hard cut S14): the logic behind the shared Schedules page, pure so desktop,
 * Android and web project schedules the same way.
 */

/** The page's views. [Rules] lists every schedule definition; [hasRange] views page through weeks. */
enum class SchedulesView(val label: String) {
    Week("Week"),
    Agenda("Agenda"),
    Timeline("Timeline"),
    History("History"),
    Rules("Rules"),
    ;

    val hasRange: Boolean get() = this == Week || this == Timeline
}

/**
 * A new schedule as the page's form submits it. [agentId] null means "the host's current agent";
 * [timezone] is the zone the cron fires in.
 */
@Immutable
data class ScheduleCreateRequest(
    val agentId: String?,
    val name: String,
    val prompt: String,
    val cron: String,
    val timezone: String,
    val recurring: Boolean = true,
) {
    /** The native `/v1/agents/{id}/schedule` body: the prompt as a user message on a recurring cron. */
    fun toCreateParams(): ScheduleCreateParams = ScheduleCreateParams(
        messages = listOf(ScheduleMessage(content = prompt, role = "user", name = name.takeIf { it.isNotBlank() })),
        schedule = ScheduleDefinition(type = "recurring", cronExpression = cron),
    )
}

/** The create form's fields: a name, the prompt the agent receives and the cadence builder. */
@Immutable
data class ScheduleFormDraft(
    val name: String = "",
    val prompt: String = "",
    val cadence: CronBuilderState = CronBuilderState(),
) {
    /** The cron the cadence builds, or null while it is incomplete. */
    val expression: String? get() = CronBuilder.toExpression(cadence)

    val isComplete: Boolean get() = expression != null && name.isNotBlank() && prompt.isNotBlank()

    /** The request this draft submits, trimmed; null while incomplete. */
    fun toRequest(agentId: String?, zone: TimeZone): ScheduleCreateRequest? {
        val cron = expression ?: return null
        if (!isComplete) return null
        return ScheduleCreateRequest(agentId = agentId, name = name.trim(), prompt = prompt.trim(), cron = cron, timezone = zone.id)
    }
}

object SchedulesPageModel {
    /**
     * Every schedule the page shows: the cron tasks (scoped to [focusedAgentId] when set; a cron with
     * no agent belongs to every agent), then the native schedule-admin schedules not already
     * represented by id. Native schedules are already agent-scoped by the library controller.
     * One-shot crons resolve to a single instant against [now], so callers must not re-key on a ticking clock.
     */
    fun defs(
        crons: List<CronTask>,
        native: List<ScheduledMessage>,
        focusedAgentId: String?,
        clock: ScheduleClock,
    ): List<ScheduleDef> {
        val scoped = if (focusedAgentId == null) crons else crons.filter { it.agentId == null || it.agentId == focusedAgentId }
        val cronDefs = ScheduleProjection.toScheduleDefs(scoped, clock.zone, clock.now)
        val cronIds = cronDefs.mapTo(HashSet()) { it.id }
        return cronDefs + ScheduleProjection.toScheduleDefsFromNative(native, clock.zone).filter { it.id !in cronIds }
    }

    /** "October 5 – 11, 2026", or across a month / year boundary "September 28 – October 4, 2026". */
    fun weekRangeLabel(weekStart: LocalDate): String {
        val end = weekStart.plus(6, DateTimeUnit.DAY)
        val month = fullMonth(weekStart.month.ordinal + 1)
        return if (end.month == weekStart.month) {
            "$month ${weekStart.day} – ${end.day}, ${weekStart.year}"
        } else {
            "$month ${weekStart.day} – ${fullMonth(end.month.ordinal + 1)} ${end.day}, ${end.year}"
        }
    }

    fun fullMonth(monthNumber: Int): String = MONTHS[(monthNumber - 1).coerceIn(0, MONTHS.lastIndex)]

    private val MONTHS = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )
}

/** The instant and zone a projection is computed at. */
@Immutable
data class ScheduleClock(val now: Instant, val zone: TimeZone)
