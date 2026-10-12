@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.schedules

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.schedules.HistorySummary
import com.letta.mobile.data.schedules.ScheduleDef
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** letta-mobile-ckmub.3.10: the schedules rail is shared; its active schedules are a roster. */
class ScheduleRailTest {
    private val now = Instant.parse("2026-10-07T12:00:00Z")
    private val soon = ScheduleDef(id = "s1", name = "Standup", cron = null, oneShotAt = now + 1.hours, active = true)
    private val later = ScheduleDef(id = "s2", name = "Digest", cron = null, oneShotAt = now + 5.hours, active = true)

    @Test
    fun theNextToFireIsTheSoonestUpcomingRun() {
        assertEquals("s1", ScheduleRailText.nextToFire(listOf(later, soon), now))
        assertEquals(null, ScheduleRailText.nextToFire(emptyList(), now))
        assertEquals("One-time", ScheduleRailText.cadence(soon))
    }

    @Test
    fun aScheduleOpensItsDetailAndBackReturnsToTheOverview() = runComposeUiTest {
        var rail by mutableStateOf<ScheduleRailState>(ScheduleRailState.Overview)
        setContent {
            MaterialTheme {
                ScheduleRail(
                    ScheduleRailParams(
                        rail = rail,
                        defs = listOf(soon, later),
                        defsById = listOf(soon, later).associateBy { it.id },
                        history = HistorySummary(overallSuccessRate = null, totalRuns = 0, schedules = emptyList()),
                        now = now,
                        zone = TimeZone.UTC,
                        onSelectSchedule = { rail = ScheduleRailState.Detail(it) },
                        onBackToOverview = { rail = ScheduleRailState.Overview },
                        onDelete = {},
                    ),
                )
            }
        }
        onNodeWithText("ACTIVE SCHEDULES").assertExists()
        onNodeWithText("Digest").performClick()
        onNodeWithText("Run now").assertExists()
        onNodeWithContentDescription("Overview").performClick()
        onNodeWithText("ACTIVE SCHEDULES").assertExists()
    }
}
