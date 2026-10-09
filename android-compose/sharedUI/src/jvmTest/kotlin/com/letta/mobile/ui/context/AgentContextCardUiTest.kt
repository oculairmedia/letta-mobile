@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.context

import androidx.compose.material3.MaterialTheme
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
    private val actions = AgentContextCardActions(onModelSelected = {})

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
        val deps = AgentContextCardDeps(readings(84_000), breakdown = null, compaction = null, pickerSource = null)
        setContent { MaterialTheme { AgentContextCardHost(deps, focus, actions, AgentContextPresentation.Popover) } }
        onNodeWithTag(AgentContextTags.CARD_MODEL).assertTextEquals("Claude Opus · High")
        onNodeWithTag(AgentContextTags.CARD_USAGE).assertTextEquals("42% of 200k used")
    }

    @Test
    fun theSheetIsTotalOnlyWithoutABreakdownAndHidesCompactWithoutARoute() = runComposeUiTest {
        val deps = AgentContextCardDeps(readings(84_000), breakdown = null, compaction = null, pickerSource = null)
        setContent { MaterialTheme { AgentContextCardHost(deps, focus, actions, AgentContextPresentation.Popover) } }
        onNodeWithTag(AgentContextTags.CARD).performClick()
        onNodeWithTag(AgentContextTags.SCOPE).assertTextEquals("Applies to this agent")
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
        val deps = AgentContextCardDeps(readings(84_000), ContextBreakdownLoader { overview }, compaction = null, pickerSource = null)
        setContent { MaterialTheme { AgentContextCardHost(deps, focus, actions, AgentContextPresentation.Popover) } }
        onNodeWithTag(AgentContextTags.CARD).performClick()
        waitForIdle()
        onNodeWithTag(AgentContextTags.PROVENANCE).assertTextEquals("Estimated, matched to provider total")
        onNodeWithText("Tools & other").assertExists()
        onNodeWithText("Memory blocks").assertExists()
    }

    @Test
    fun compactingReportsWhatItCameTo() = runComposeUiTest {
        val result = ConversationCompactResult("execute_command", messagesBefore = 48, messagesAfter = 12, contextTokensBefore = 60_000, contextTokensAfter = 10_000)
        val repository = repository(CompactionOutcome.Compacted(result))
        val readings = readings(84_000)
        val deps = AgentContextCardDeps(readings, breakdown = null, compaction = CompactionController(repository, readings), pickerSource = null)
        setContent { MaterialTheme { AgentContextCardHost(deps, focus, actions, AgentContextPresentation.Popover) } }
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
        val deps = AgentContextCardDeps(readings, null, CompactionController(repository(CompactionOutcome.Unsupported), readings), null)
        setContent { MaterialTheme { AgentContextCardHost(deps, focus, actions, AgentContextPresentation.Popover) } }
        onNodeWithTag(AgentContextTags.CARD).performClick()
        onNodeWithTag(AgentContextTags.COMPACT).performClick()
        waitForIdle()
        onNodeWithTag(AgentContextTags.COMPACT).assertDoesNotExist()
    }
}
