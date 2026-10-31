@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.pages.schedules

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentSummary
import com.letta.mobile.data.schedules.CronTask
import com.letta.mobile.data.schedules.ScheduleClock
import com.letta.mobile.data.schedules.ScheduleLibraryState
import com.letta.mobile.data.schedules.SchedulesView
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.ProvideLettaHost
import com.letta.mobile.ui.theme.SharedMaterialTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

/** The Schedules page with a realistic set of schedules, rendered to build/schedules-render/ for review. */
class SchedulesPageRenderTest {
    @Test
    fun renderEveryWidth() {
        listOf(PHONE, BELOW, ABOVE, WIDE).forEach { width ->
            listOf(null, SchedulesView.Agenda, SchedulesView.Week, SchedulesView.Month).forEach { view ->
                render("schedules-$width-${view?.name?.lowercase() ?: "default"}.png", width, view)
            }
        }
    }

    private fun render(name: String, width: Int, view: SchedulesView?) = runDesktopComposeUiTest(width = width, height = HEIGHT) {
        setContent {
            SharedMaterialTheme(darkTheme = true) {
                ProvideLettaHost(if (width < BELOW + 1) LettaHost.Touch else LettaHost.Pointer) {
                    Surface(Modifier.fillMaxSize()) {
                        SchedulesPage(
                            state = state(),
                            actions = SchedulesPageActions(),
                            options = SchedulesPageOptions(initialView = view, fixedClock = CLOCK, touch = width < BELOW + 1),
                        )
                    }
                }
            }
        }
        waitForIdle()
        val file = File("build/schedules-render").apply { mkdirs() }.resolve(name)
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
        println("schedules snapshot: ${file.absolutePath}")
    }

    private fun state() = SchedulesPageState(
        library = ScheduleLibraryState(isLoading = false, agents = AGENTS, selectedAgentId = "agent-1"),
        crons = CRONS,
        canCreate = true,
    )

    private companion object {
        const val PHONE = 360
        const val BELOW = 700
        const val ABOVE = 760
        const val WIDE = 1200
        const val HEIGHT = 900
        val ZONE = TimeZone.of("America/New_York")
        val CLOCK = ScheduleClock(Instant.parse("2026-10-28T14:20:00Z"), ZONE)
        // Seeded mascot colours: agent-1 blue, agent-12 purple, agent-4 orange.
        val AGENT_IDS = listOf("agent-1", "agent-12", "agent-4")
        val AGENTS = listOf("Scout", "Planner", "Archivist").mapIndexed { i, n -> AgentSummary(id = AgentId(AGENT_IDS[i]), name = n) }
        val CRONS = listOf(
            CronTask(
                "c1", "agent-1", name = "Morning standup", cron = "0 9 * * 1-5", recurring = true, timezone = "America/New_York",
                lastFiredAt = "2026-10-28T13:00:00Z",
            ),
            CronTask("c2", "agent-12", name = "Inbox triage", cron = "*/15 9-17 * * 1-5", recurring = true, timezone = "America/New_York"),
            CronTask("c3", "agent-4", name = "Nightly memory sweep", cron = "30 2 * * *", recurring = true, timezone = "America/New_York"),
            CronTask(
                "c4", "agent-12", name = "Weekly report", cron = "0 16 * * 5", recurring = true, timezone = "America/New_York",
                status = "paused",
            ),
            CronTask(
                "c5", "agent-1", name = "Lunch digest", cron = "30 12 * * *", recurring = true, timezone = "UTC",
                missedAt = "2026-10-27T12:30:00Z",
            ),
            CronTask("c8", "agent-12", name = "Calendar sync", cron = "0 9 * * *", recurring = true, timezone = "America/New_York"),
            CronTask("c6", "agent-4", name = "Heartbeat", cron = "0 * * * *", recurring = true, timezone = "America/New_York"),
            CronTask("c7", "agent-1", name = "Dentist reminder", cron = "0 15 29 10 *", recurring = false, timezone = "America/New_York"),
        )
    }
}
