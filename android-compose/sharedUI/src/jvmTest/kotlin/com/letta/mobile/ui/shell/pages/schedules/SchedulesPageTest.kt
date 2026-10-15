@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.pages.schedules

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentSummary
import com.letta.mobile.data.model.ScheduleDefinition
import com.letta.mobile.data.model.ScheduleMessage
import com.letta.mobile.data.model.SchedulePayload
import com.letta.mobile.data.model.ScheduledMessage
import com.letta.mobile.data.schedules.CronTask
import com.letta.mobile.data.schedules.ScheduleClock
import com.letta.mobile.data.schedules.ScheduleCreateRequest
import com.letta.mobile.data.schedules.ScheduleLibraryState
import com.letta.mobile.data.schedules.SchedulesView
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.LettaPaneDefaults
import com.letta.mobile.ui.host.LettaPaneTags
import com.letta.mobile.ui.host.LocalLettaHost
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** letta-mobile-ckmub.3.16: the shared Schedules page on a desktop pane and a phone. */
class SchedulesPageTest {
    private val clock = ScheduleClock(Instant.parse("2026-10-07T12:00:00Z"), TimeZone.UTC)
    private val standup = CronTask(id = "c1", agentId = "a1", name = "Standup", cron = "0 9 * * 1-5", recurring = true, timezone = "UTC")
    private val digest = CronTask(id = "c2", agentId = "a1", name = "Digest", cron = "0 18 * * *", recurring = true, timezone = "UTC")

    private fun state(
        crons: List<CronTask> = listOf(standup, digest),
        canCreate: Boolean = true,
        focusedAgentId: String? = "a1",
        library: ScheduleLibraryState = ScheduleLibraryState(isLoading = false, selectedAgentId = "a1"),
    ) = SchedulesPageState(library = library, crons = crons, focusedAgentId = focusedAgentId, canCreate = canCreate)

    private fun ComposeUiTest.show(
        state: SchedulesPageState,
        actions: SchedulesPageActions = SchedulesPageActions(),
        width: Dp = WIDE,
        initialView: SchedulesView = SchedulesView.Week,
    ) {
        // Pointer containers (dialogs) everywhere, the phone's sheet included: the test draws no window insets.
        val host = LettaHost.Pointer.copy(sheet = LettaPaneDefaults.Dialog)
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLettaHost provides host) {
                    Box(Modifier.width(width).height(PAGE_HEIGHT)) {
                        SchedulesPage(state, actions, options = SchedulesPageOptions(initialView = initialView, fixedClock = clock))
                    }
                }
            }
        }
    }

    @Test
    fun eachTabShowsItsView() = runComposeUiTest {
        show(state())
        onNodeWithTag(SchedulesPageTags.view(SchedulesView.Week)).assertExists()
        SchedulesView.entries.forEach { view ->
            onNodeWithTag(SchedulesPageTags.tab(view)).performClick()
            onNodeWithTag(SchedulesPageTags.view(view)).assertExists()
        }
        onNodeWithTag(SchedulesPageTags.rule("c1")).assertExists()
        onNodeWithTag(SchedulesPageTags.rule("c2")).assertExists()
    }

    @Test
    fun weekNavigationPagesTheRange() = runComposeUiTest {
        show(state())
        onNodeWithText("October 5 – 11, 2026").assertExists()
        onNodeWithTag(SchedulesPageTags.NEXT_WEEK).performClick()
        onNodeWithText("October 12 – 18, 2026").assertExists()
        onNodeWithTag(SchedulesPageTags.PREVIOUS_WEEK).performClick()
        onNodeWithTag(SchedulesPageTags.PREVIOUS_WEEK).performClick()
        onNodeWithText("September 28 – October 4, 2026").assertExists()
    }

    @Test
    fun theFormBuildsACronAndCreatesForTheFocusedAgent() = runComposeUiTest {
        val created = mutableListOf<ScheduleCreateRequest>()
        show(state(), SchedulesPageActions(onCreate = { created += it }))
        onNodeWithTag(SchedulesPageTags.NEW).performClick()
        onNodeWithTag(SchedulesPageTags.FORM_SUBMIT).assertIsNotEnabled()
        onNodeWithTag(SchedulesPageTags.FORM_NAME).performTextInput("Briefing")
        onNodeWithTag(SchedulesPageTags.FORM_PROMPT).performTextInput("Brief me")
        onNodeWithTag(SchedulesPageTags.cadence("Hourly")).performClick()
        onNodeWithText("Every hour at :00").assertExists()
        onNodeWithTag(SchedulesPageTags.cadence("Daily")).performClick()
        onNodeWithTag(SchedulesPageTags.FORM_SUBMIT).assertIsEnabled().performClick()
        assertEquals(listOf(ScheduleCreateRequest("a1", "Briefing", "Brief me", "0 9 * * *", "UTC")), created)
        onNodeWithTag(SchedulesPageTags.FORM).assertDoesNotExist()
    }

    @Test
    fun withoutCreateTheEmptyPageSaysWhereSchedulesComeFrom() = runComposeUiTest {
        show(state(crons = emptyList(), canCreate = false))
        onNodeWithTag(SchedulesPageTags.NEW).assertDoesNotExist()
        onNodeWithTag(SchedulesPageTags.EMPTY).assertExists()
        onNodeWithText("Schedules created elsewhere will appear here.").assertExists()
    }

    @Test
    fun deletingFromARuleAsksFirst() = runComposeUiTest {
        val deleted = mutableListOf<CronTaskId>()
        show(state(), SchedulesPageActions(onDeleteCron = { deleted += it }), initialView = SchedulesView.Rules)
        onNodeWithTag(SchedulesPageTags.rule("c2")).performClick()
        onNodeWithText("Delete").performClick()
        onNodeWithTag(LettaPaneTags.CONFIRM).assertExists()
        assertEquals(emptyList(), deleted)
        onNode(hasText("Delete") and hasAnyAncestor(hasTestTag(LettaPaneTags.CONFIRM))).performClick()
        assertEquals(listOf(CronTaskId("c2")), deleted)
        onNodeWithTag(LettaPaneTags.CONFIRM).assertDoesNotExist()
    }

    @Test
    fun aCronIdEqualToAScheduleIdDeletesOnlyTheCron() = runComposeUiTest {
        val crons = mutableListOf<CronTaskId>()
        val schedules = mutableListOf<NativeScheduleRef>()
        val library = ScheduleLibraryState(isLoading = false, selectedAgentId = "a1", schedules = listOf(native("c2", "a1")))
        val actions = SchedulesPageActions(onDeleteSchedule = { schedules += it }, onDeleteCron = { crons += it })
        show(state(library = library), actions, initialView = SchedulesView.Rules)
        onNodeWithTag(SchedulesPageTags.rule("c2")).performClick()
        onNodeWithText("Delete").performClick()
        onNode(hasText("Delete") and hasAnyAncestor(hasTestTag(LettaPaneTags.CONFIRM))).performClick()
        assertEquals(listOf(CronTaskId("c2")), crons)
        assertEquals(emptyList(), schedules)
    }

    @Test
    fun aNativeScheduleDeletesWithItsAgent() {
        val library = ScheduleLibraryState(isLoading = false, selectedAgentId = "a1", schedules = listOf(native("s1", "a1")))
        assertEquals(
            ScheduleDeleteTarget.Native(NativeScheduleRef(agentId = "a1", scheduleId = "s1")),
            state(library = library).deleteTarget("s1"),
        )
    }

    @Test
    fun anotherAgentsCronDoesNotShadowTheFocusedAgentsSchedule() {
        val otherAgentsCron = CronTask(id = "s1", agentId = "a2", name = "Other", cron = "0 9 * * *", recurring = true)
        val library = ScheduleLibraryState(isLoading = false, selectedAgentId = "a1", schedules = listOf(native("s1", "a1")))
        assertEquals(
            ScheduleDeleteTarget.Native(NativeScheduleRef(agentId = "a1", scheduleId = "s1")),
            state(crons = listOf(otherAgentsCron), library = library).deleteTarget("s1"),
        )
        assertEquals(null, state(library = library).deleteTarget("gone"))
    }

    private fun native(id: String, agentId: String) = ScheduledMessage(
        id = id,
        agentId = agentId,
        message = SchedulePayload(messages = listOf(ScheduleMessage(content = "hello", role = "user"))),
        schedule = ScheduleDefinition(type = "recurring", cronExpression = "0 9 * * *"),
    )

    @Test
    fun aPhoneOpensTheScheduleDetailInASheet() = runComposeUiTest {
        show(state(), width = COMPACT, initialView = SchedulesView.Rules)
        onNodeWithTag(SchedulesPageTags.DETAIL_SHEET).assertDoesNotExist()
        onNodeWithTag(SchedulesPageTags.rule("c1")).performClick()
        onNodeWithTag(SchedulesPageTags.DETAIL_SHEET).assertExists()
        onNodeWithText("Run now").assertExists()
    }

    @Test
    fun anUnfocusedPageOffersTheAgentsAndAFocusedOneSelectsItsAgent() = runComposeUiTest {
        val selected = mutableListOf<String>()
        val agents = listOf(AgentSummary(AgentId("a1"), "Ada"), AgentSummary(AgentId("a2"), "Bo"))
        val library = ScheduleLibraryState(isLoading = false, agents = agents, selectedAgentId = "a2")
        show(state(focusedAgentId = "a1", library = library), SchedulesPageActions(onSelectAgent = { selected += it }))
        waitForIdle()
        assertEquals(listOf("a1"), selected)
        onNodeWithTag(SchedulesPageTags.agent("a2")).assertDoesNotExist()
    }

    @Test
    fun agentChipsSwitchTheLibraryAgent() = runComposeUiTest {
        val selected = mutableListOf<String>()
        val agents = listOf(AgentSummary(AgentId("a1"), "Ada"), AgentSummary(AgentId("a2"), "Bo"))
        val library = ScheduleLibraryState(isLoading = false, agents = agents, selectedAgentId = "a1")
        show(state(focusedAgentId = null, library = library), SchedulesPageActions(onSelectAgent = { selected += it }))
        onNodeWithTag(SchedulesPageTags.agent("a2")).performClick()
        assertEquals(listOf("a2"), selected)
    }

    private companion object {
        val WIDE = 1200.dp
        val COMPACT = 400.dp
        val PAGE_HEIGHT = 800.dp
    }
}
