@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.pages.runs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.Run
import com.letta.mobile.data.model.Step
import com.letta.mobile.data.runs.RunDetail
import com.letta.mobile.data.runs.RunMonitorState
import com.letta.mobile.data.settings.SettingsRow
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.LettaPaneDefaults
import com.letta.mobile.ui.host.LocalLettaHost
import com.letta.mobile.ui.settings.SettingsSectionTags
import kotlin.test.Test
import kotlin.test.assertEquals

/** letta-mobile-ckmub.2.5 (S17): the shared Runs page's states - loading, empty, error, list, detail. */
class RunsPageTest {
    private val running = Run(id = "run-1", agentId = "agent-a", conversationId = "conv-1", status = "running")
    private val failed = Run(id = "run-2", agentId = "agent-b", status = "failed", stopReason = "error")

    private fun ComposeUiTest.show(state: RunsPageState, actions: RunsPageActions = RunsPageActions()) {
        val host = LettaHost.Pointer.copy(sheet = LettaPaneDefaults.Dialog)
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLettaHost provides host) {
                    Box(Modifier.width(900.dp).height(800.dp)) { RunsPage(state, actions) }
                }
            }
        }
    }

    private fun loaded(vararg runs: Run, detail: RunDetail? = null, canManage: Boolean = false) = RunsPageState(
        monitor = RunMonitorState(runs = runs.toList(), loading = false, detail = detail),
        agentNames = mapOf("agent-a" to "Alice"),
        canManageRuns = canManage,
    )

    @Test
    fun aFirstLoadShowsTheRostersProgress() = runComposeUiTest {
        show(RunsPageState(monitor = RunMonitorState(loading = true)))
        onNodeWithTag(RunsPageTags.LIST.loading).assertExists()
    }

    @Test
    fun anEmptyHostSaysSo() = runComposeUiTest {
        show(loaded())
        onNodeWithTag(RunsPageTags.LIST.empty).assertExists()
        onNodeWithText("No runs yet").assertExists()
    }

    @Test
    fun aListThatCannotLoadOffersRetry() = runComposeUiTest {
        val calls = mutableListOf<String>()
        show(RunsPageState(monitor = RunMonitorState(loading = false, error = "host down")), RunsPageActions(onRefresh = { calls += "refresh" }))
        onNodeWithTag(RunsPageTags.ERROR).assertExists()
        onNodeWithTag(RunsPageTags.RETRY).performClick()
        assertEquals(listOf("refresh"), calls)
    }

    @Test
    fun theListNamesAgentsAndReportsTheFilterAndSearch() = runComposeUiTest {
        val calls = mutableListOf<String>()
        show(
            loaded(running, failed),
            RunsPageActions(
                onActiveOnlyChange = { calls += "active:$it" },
                onQueryChange = { calls += "query:$it" },
                onOpenRun = { calls += "open:$it" },
            ),
        )
        onNodeWithText("Alice").assertExists()
        onNodeWithText("agent-b").assertExists()
        onNodeWithText("2 runs · 1 active · 1 failed").assertExists()
        onNodeWithTag(RunsPageTags.ACTIVE_ONLY).performClick()
        onNodeWithTag(RunsPageTags.SEARCH).performTextInput("x")
        onNodeWithTag(RunsPageTags.LIST.row("runs:run-2").value).performClick()
        assertEquals(listOf("active:true", "query:x"), calls.take(2))
        assertEquals("open:run-2", calls.last())
    }

    @Test
    fun anIrohRunShowsItsStepsAndNoDelete() = runComposeUiTest {
        val detail = RunDetail(run = running, steps = listOf(Step(id = "s1", model = "gpt", totalTokens = 9)))
        val opened = mutableListOf<String>()
        show(loaded(running, detail = detail), RunsPageActions(onOpenConversation = { agent, conversation -> opened += "$agent/$conversation" }))
        onNodeWithTag(RunsPageTags.DETAIL).assertExists()
        onNodeWithText("gpt · 9 tokens").assertExists()
        onNodeWithTag(SettingsSectionTags.ROW_PREFIX + RunDetailTags.DELETE).assertDoesNotExist()
        onNodeWithTag(SettingsSectionTags.ROW_PREFIX + RunDetailTags.LIMITED).assertExists()
        onNodeWithTag(SettingsSectionTags.ACTION_PREFIX + RunDetailTags.OPEN_CONVERSATION).performScrollTo().performClick()
        assertEquals(listOf("agent-a/conv-1"), opened)
    }

    @Test
    fun aFullHostCancelsAnActiveRun() = runComposeUiTest {
        val cancelled = mutableListOf<String>()
        show(loaded(running, detail = RunDetail(run = running, full = true), canManage = true), RunsPageActions(onCancelRun = { cancelled += it }))
        onNodeWithTag(SettingsSectionTags.ROW_PREFIX + RunDetailTags.DELETE).assertExists()
        onNodeWithTag(SettingsSectionTags.ACTION_PREFIX + RunDetailTags.CANCEL).performScrollTo().performClick()
        assertEquals(listOf("run-1"), cancelled)
    }

    @Test
    fun factsLeaveOutWhatTheHostDidNotReport() {
        val facts = runFacts(RunDetail(run = failed), agentName = "agent-b")
        assertEquals(listOf("run-id", "run-agent", "run-status", "run-stop"), facts.map(SettingsRow.Info::key))
    }
}
