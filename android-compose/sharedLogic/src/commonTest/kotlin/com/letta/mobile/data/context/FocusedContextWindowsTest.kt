package com.letta.mobile.data.context

import com.letta.mobile.data.context.estimate.ESTIMATE_SOURCE
import com.letta.mobile.data.context.limit.AppliedContextLimit
import com.letta.mobile.data.context.limit.ContextLimitScope
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ContextWindowOverview
import com.letta.mobile.data.model.LlmConfig
import com.letta.mobile.data.model.LlmModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** letta-mobile-joigh: which window the drawer meter and the limit slider use. */
class FocusedContextWindowsTest {
    private val opus = "anthropic/claude-opus-5-5"
    private val hostCatalog = listOf(
        LlmModel(id = "opus-5.5", handle = opus, contextWindow = 200_000),
        LlmModel(id = "opus-5.5-1m", handle = opus, contextWindow = 1_000_000),
    )
    private val agent = Agent(id = AgentId("agent-1"), name = "a", model = "lmstudio/MiniMax-M3", llmConfig = LlmConfig(contextWindow = 128_000))

    /**
     * Regression (device, S5): a conversation switched to Opus 5.5 1M through the host catalog,
     * which the chat's own model list did not carry, had no window — the sheet read "112.8k" and
     * "0.0%" while its model list showed 1M.
     */
    @Test
    fun aPickTheChatListDoesNotCarryTakesTheSheetCatalogsWindow() {
        val chatList = listOf(LlmModel(id = "minimax", handle = "lmstudio/MiniMax-M3", contextWindow = 128_000))
        val focusWindow = contextWindowTokensOf(agent, chatList, modelOverride = opus)
        assertNull(focusWindow, "the precondition of the bug")

        val modelMax = modelCatalogWindowOf(chatList, opus) ?: modelCatalogWindowOf(hostCatalog, opus)
        val windows = FocusedContextWindows.of(null, opus, focusWindow, modelMax)
        assertEquals(1_000_000, windows.window)
        assertEquals(1_000_000, windows.modelMax)

        val model = AgentContextCardModel.present(
            AgentContextCardInputs(
                modelLabel = "Opus 5.5",
                effort = null,
                meter = ContextMeter.of(112_800, windows.meterWindow),
                conversationIsDefault = false,
                compactSupported = null,
                compacting = false,
                turnRunning = false,
            ),
        )
        assertEquals(11, model.usedPercent)
        assertEquals(1_000_000, model.meter?.usage?.maxTokens)
    }

    @Test
    fun rowsSharingAHandleResolveToTheLargestWindowAndAnExactTokenWins() {
        assertEquals(1_000_000, modelCatalogWindowOf(hostCatalog, opus))
        assertEquals(200_000, modelCatalogWindowOf(hostCatalog, "opus-5.5"))
        assertNull(modelCatalogWindowOf(hostCatalog, "openai/unknown"))
        assertNull(modelCatalogWindowOf(hostCatalog, null))
    }

    @Test
    fun aJustAppliedLimitWinsForItsModelOnly() {
        val applied = AppliedContextLimit(400_000, ContextLimitScope.Conversation, opus)
        assertEquals(400_000, FocusedContextWindows.of(applied, opus, 1_000_000, null).pinned)
        assertNull(FocusedContextWindows.of(applied, "openai/gpt-sol", 1_000_000, null).pinned)
    }

    @Test
    fun thePinnedLimitBeatsAStaleHostRecordInTheMeter() {
        val staleRecord = ContextWindowOverview(contextWindowSizeMax = 1_000_000, contextWindowSizeCurrent = 112_800, source = ESTIMATE_SOURCE)
        val windows = FocusedContextWindows(pinned = 400_000, window = 1_000_000, modelMax = 1_000_000)
        val meter = ContextMeter.of(112_800, windows.meterWindow, windows.recordWith(staleRecord))!!
        assertEquals(400_000, meter.usage.maxTokens)
        assertEquals(ContextMeter.autoCompactAt(400_000), meter.autoCompactAt)
        assertEquals(400_000, windows.current(staleRecord))
        assertEquals(1_000_000, windows.copy(pinned = null).current(staleRecord))
    }

    @Test
    fun theHostsOwnFigureStillComesBeforeTheCatalog() {
        val windows = FocusedContextWindows.of(null, "lmstudio/MiniMax-M3", 128_000, 1_000_000)
        assertEquals(128_000, windows.window)
        assertEquals(1_000_000, windows.modelMax)
    }
}
