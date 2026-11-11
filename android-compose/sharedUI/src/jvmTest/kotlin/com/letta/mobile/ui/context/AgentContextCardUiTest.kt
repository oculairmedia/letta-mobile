@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.context

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.LocalLettaHost
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.compaction.CompactionController
import com.letta.mobile.data.compaction.CompactionOutcome
import com.letta.mobile.data.compaction.CompactionRepository
import com.letta.mobile.data.compaction.CompactionRequest
import com.letta.mobile.data.compaction.ConversationCompactResult
import com.letta.mobile.data.context.ContextBreakdownLoader
import com.letta.mobile.data.context.ContextTokenReadings
import com.letta.mobile.data.model.ContextWindowOverview
import com.letta.mobile.data.transport.ServerFrame
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** letta-mobile-3io8k: the drawer context card and its sheet. */
class AgentContextCardUiTest {
    private val focus = AgentContextFocus(
        agentId = "agent-1",
        conversationId = null,
        modelLabel = "Claude Opus",
        modelValue = null,
        effort = "high",
        windowTokens = 200_000,
        turnRunning = false,
    )

    private fun readings(total: Long) = ContextTokenReadings().also {
        runBlocking { it.record(ServerFrame.UsageStatistics(agentId = "agent-1", conversationId = "default", contextTokens = total)) }
    }

    private fun repository(outcome: CompactionOutcome) = object : CompactionRepository {
        var calls = 0
        override suspend fun compact(request: CompactionRequest): CompactionOutcome {
            calls++
            return outcome
        }
    }

    @Test
    fun theCardShowsTheModelEffortAndHowFullTheWindowIs() = runComposeUiTest {
        val deps = AgentContextCardDeps(readings(84_000), breakdown = null, compaction = null)
        setContent { PointerCard(deps, focus) }
        onNodeWithTag(AgentContextTags.CARD_MODEL, useUnmergedTree = true).assertTextEquals("Claude Opus · High")
        onNodeWithTag(AgentContextTags.CARD_USAGE, useUnmergedTree = true).assertTextEquals("42% of 200k used")
    }

    @Test
    fun theSheetIsTotalOnlyWithoutABreakdownAndHidesCompactWithoutARoute() = runComposeUiTest {
        val deps = AgentContextCardDeps(readings(84_000), breakdown = null, compaction = null)
        setContent { PointerCard(deps, focus) }
        onNodeWithTag(AgentContextTags.CARD).performClick()
        onNodeWithTag(AgentContextTags.SCOPE).assertTextEquals("Changes the agent's model")
        onNodeWithTag(AgentContextTags.PROVENANCE).assertTextEquals("Total only")
        onNodeWithTag(AgentContextTags.HINT).assertExists()
        onNodeWithTag(AgentContextTags.COMPACT).assertDoesNotExist()
    }

    @Test
    fun anEstimatedBreakdownIsMatchedToTheStreamedTotal() = runComposeUiTest {
        val overview = ContextWindowOverview(
            contextWindowSizeMax = 200_000,
            numTokensSystem = 4_000,
            numTokensCoreMemory = 2_000,
            numTokensMessages = 30_000,
            source = "estimate",
            calibrated = false,
        )
        val deps = AgentContextCardDeps(readings(84_000), ContextBreakdownLoader { overview }, compaction = null)
        setContent { PointerCard(deps, focus) }
        onNodeWithTag(AgentContextTags.CARD).performClick()
        waitForIdle()
        onNodeWithTag(AgentContextTags.PROVENANCE).assertTextEquals("Estimated, matched to provider total; split is approximate")
        onNodeWithText("Other").assertExists()
        onNodeWithText("Memory blocks").assertExists()
    }

    @Test
    fun usageAndOverheadOnlyBreakdownsNameToolsWithHonestCaptions() {
        val overview = ContextWindowOverview(
            contextWindowSizeMax = 200_000,
            contextWindowSizeCurrent = 84_000,
            numTokensSystem = 4_000,
            numTokensCoreMemory = 2_000,
            numTokensMessages = 60_000,
            numTokensFunctionsDefinitions = 18_000,
            numTokensToolsAndHarness = 18_000,
            calibrationSource = "conversation_usage",
            messageCharsPerToken = 2.16,
            source = "estimate",
            calibrated = true,
        )
        val overheadOnly = overview.copy(
            numTokensMessages = 540,
            numTokensFunctionsDefinitions = 77_460,
            numTokensToolsAndHarness = 77_460,
            calibrationSource = null,
            messageCharsPerToken = null,
            overheadSource = "recorded_usage",
        )
        val cases = listOf(
            overview to "Measured from this conversation's usage",
            overview.copy(calibrationApproximate = true) to "Measured from this conversation's usage; split is approximate",
            overheadOnly to "Overhead measured; messages estimated; split is approximate",
        )
        cases.forEach { (reading, caption) ->
            runComposeUiTest {
                val deps = AgentContextCardDeps(readings(84_000), ContextBreakdownLoader { reading }, compaction = null)
                setContent { PointerCard(deps, focus) }
                onNodeWithTag(AgentContextTags.CARD).performClick()
                waitForIdle()
                onNodeWithTag(AgentContextTags.PROVENANCE).assertTextEquals(caption)
                onNodeWithText("Tools and harness").assertExists()
                onNodeWithText("Other").assertDoesNotExist()
            }
        }
    }

    @Test
    fun compactingReportsWhatItCameTo() = runComposeUiTest {
        val result = ConversationCompactResult("execute_command", messagesBefore = 48, messagesAfter = 12, contextTokensBefore = 60_000, contextTokensAfter = 10_000)
        val repository = repository(CompactionOutcome.Compacted(result))
        val readings = readings(84_000)
        val deps = AgentContextCardDeps(readings, breakdown = null, compaction = CompactionController(repository, readings))
        setContent { PointerCard(deps, focus) }
        onNodeWithTag(AgentContextTags.CARD).performClick()
        onNodeWithTag(AgentContextTags.COMPACT).assertIsEnabled().performClick()
        waitForIdle()
        onNodeWithTag(AgentContextTags.NOTICE).assertTextEquals("Compacted: 48 → 12 messages")
        onNodeWithTag(AgentContextTags.PROVENANCE).assertTextEquals("Estimated after compaction")
        assertEquals(1, repository.calls)
        assertEquals(34_000, readings.latest("agent-1", "default"))
    }

    @Test
    fun anUnsupportedBackendHidesCompactAfterTheFirstTry() = runComposeUiTest {
        val readings = readings(84_000)
        val deps = AgentContextCardDeps(readings, null, CompactionController(repository(CompactionOutcome.Unsupported), readings))
        setContent { PointerCard(deps, focus) }
        onNodeWithTag(AgentContextTags.CARD).performClick()
        onNodeWithTag(AgentContextTags.COMPACT).performClick()
        waitForIdle()
        onNodeWithTag(AgentContextTags.COMPACT).assertDoesNotExist()
    }
}

/** The card under the desktop pointer host, with no inspector frame: its detail opens as the host's dialog. */
@Composable
private fun PointerCard(deps: AgentContextCardDeps, focus: AgentContextFocus) {
    MaterialTheme {
        CompositionLocalProvider(LocalLettaHost provides LettaHost.Pointer) {
            AgentContextCardHost(AgentContextCardBinding(deps, picker = null), focus)
        }
    }
}
