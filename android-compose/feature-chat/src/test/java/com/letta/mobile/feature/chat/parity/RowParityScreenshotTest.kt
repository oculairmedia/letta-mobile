package com.letta.mobile.feature.chat.parity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.chat.projection.ChatDisplayMode
import com.letta.mobile.data.chat.projection.IncrementalChatRenderItemsCache
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.feature.chat.NoOpChatActions
import com.letta.mobile.feature.chat.screen.ChatComposer
import com.letta.mobile.feature.chat.screen.ChatMessageList
import com.letta.mobile.feature.chat.screen.activeRunActivity
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import com.letta.mobile.ui.chat.surface.ChatPlatformStyle
import com.letta.mobile.ui.chat.surface.ChatSurface
import com.letta.mobile.ui.chat.surface.ChatSurfaceAppearance
import com.letta.mobile.ui.chat.surface.ChatToolDetails
import com.letta.mobile.ui.chat.surface.timeline.ChatTimelineSnapshot
import com.letta.mobile.ui.components.ThinkingTextToken
import com.letta.mobile.ui.theme.LettaChatTheme
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LettaTheme
import java.io.File
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * letta-mobile-ckmub.1 (hard cut S1): the side-by-side parity harness for the chat rows. Every row
 * type in [ParityRow.all] is drawn twice from the same slice of [RowParityFixture]: through the
 * LEGACY Android timeline (feature-chat ChatMessageList, which dispatches to RunBlock,
 * ChatReasoning, ProjectedToolTimeline, the bubbles and the approval card; the composer's thinking
 * token and mascot companion for the thinking rows) and through the SHARED one (sharedUI
 * ChatTimelineSnapshot -> ChatRow*; the ChatSurface page for the mascot seat). Phone width, light
 * and dark.
 *
 * For each row and theme it writes `<theme>/<row>.legacy.png`, `.shared.png`, `.diff.png` (red =
 * differing pixel) and `.side-by-side.png`, plus `report.md` (row type -> pixel diff %), under
 * build/outputs/roborazzi/row-parity. It records no baseline and fails on no diff: it only fails
 * when nothing could be rendered at all. A row that throws is reported, not fatal.
 *
 * Off by default so the regular unit test run stays fast; run it with
 * `./gradlew --no-daemon :feature-chat:rowParityReport` (sets `letta.rowParity=true`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, qualifiers = "w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Tag("screenshot")
class RowParityScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private enum class Side { LEGACY, SHARED }

    private data class Target(val row: ParityRow, val side: Side)

    @Before
    fun onlyWhenAsked() {
        assumeTrue("row parity harness: run :feature-chat:rowParityReport", System.getProperty(ENABLED_PROPERTY) == "true")
    }

    @Test
    fun light() = runAll(AppTheme.LIGHT, "light")

    @Test
    fun dark() = runAll(AppTheme.DARK, "dark")

    private fun runAll(theme: AppTheme, themeName: String) {
        var target by mutableStateOf<Target?>(null)
        // The shared page's mascot and thinking sweep never stop animating, so the page never
        // idles on an auto-advancing clock: step it by hand before each capture.
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            LettaTheme(appTheme = theme, themePreset = ThemePreset.DEFAULT, dynamicColor = false) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    target?.let { current -> key(current) { Draw(current) } }
                }
            }
        }
        val dir = outputDir()
        val output = ThemeOutput(themeName, dir.resolve(themeName))
        val results = ParityRow.all.map { row ->
            runCatching {
                val legacy = capture { target = Target(row, Side.LEGACY) }
                val shared = capture { target = Target(row, Side.SHARED) }
                record(output, row, RowParityImages.compare(legacy, shared))
            }.getOrElse { failure ->
                ParityResult(themeName, row.id, row.label, null, null, null, "render failed: ${failure::class.simpleName}: ${failure.message?.take(MAX_NOTE)}")
            }
        }
        target = null
        RowParityReport.writeResults(dir, themeName, results)
        val report = RowParityReport.rebuild(dir, ParityRow.all.map { it.id })
        println(report)
        assertTrue("no row rendered:\n$report", results.any { it.diffPercent != null })
    }

    /** Applies [show] on the UI thread, lets it compose and settle on the manual clock, then grabs the root. */
    private fun capture(show: () -> Unit): Pixels {
        composeRule.runOnUiThread(show)
        Snapshot.sendApplyNotifications()
        repeat(SETTLE_FRAMES) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }
        composeRule.mainClock.advanceTimeBy(SETTLE_MILLIS)
        composeRule.waitForIdle()
        return Pixels.of(composeRule.onRoot().captureToImage().asAndroidBitmap())
    }

    /** Where one theme's images go, and the theme's name in the report. */
    private class ThemeOutput(val name: String, val dir: File)

    private fun record(output: ThemeOutput, row: ParityRow, result: RowParityImages.Result): ParityResult {
        val images = mapOf(
            "legacy" to result.legacy,
            "shared" to result.shared,
            "diff" to result.diff,
            "side-by-side" to RowParityImages.sideBySide(result),
        )
        images.forEach { (kind, pixels) -> RowParityImages.write(pixels, output.dir.resolve("${row.id}.$kind.png")) }
        val density = composeRule.density.density
        return ParityResult(
            theme = output.name,
            rowId = row.id,
            label = row.label,
            diffPercent = result.summary.percent,
            legacyHeightDp = (result.summary.legacyHeightPx / density).toInt(),
            sharedHeightDp = (result.summary.sharedHeightPx / density).toInt(),
        )
    }

    @Composable
    private fun Draw(target: Target) {
        val row = target.row
        val state = row.state
        when (row.layout) {
            ParityLayout.TIMELINE, ParityLayout.THINKING -> when (target.side) {
                Side.LEGACY -> LegacyTimeline(state, thinkingToken = row.layout == ParityLayout.THINKING)
                Side.SHARED -> ChatTimelineSnapshot(
                    state = state,
                    actions = NoOpChatActions,
                    appearance = AndroidAppearance,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            ParityLayout.THINKING_MASCOT -> WithParityMascot(RowParityFixture.AGENT_ID) {
                when (target.side) {
                    Side.LEGACY -> LegacyPageBottom(state)
                    Side.SHARED -> SharedPage(state)
                }
            }
        }
    }

    /** ChatScreen's message area; with [thinkingToken], the composer's thinking token under it. */
    @Composable
    private fun LegacyTimeline(state: ChatUiState, thinkingToken: Boolean) {
        LettaChatTheme {
            Column(Modifier.fillMaxSize()) {
                LegacyList(state, Modifier.weight(1f).fillMaxWidth())
                if (thinkingToken) LegacyThinkingToken(state, leadingInset = true)
            }
        }
    }

    /** ChatScreen's list over its composer, whose companion row holds the mascot and the token. */
    @Composable
    private fun LegacyPageBottom(state: ChatUiState) {
        LettaChatTheme {
            Column(Modifier.fillMaxSize()) {
                LegacyList(state, Modifier.weight(1f).fillMaxWidth())
                ChatComposer(
                    inputText = "",
                    pendingAttachments = persistentListOf(),
                    isStreaming = state.isStreaming,
                    canSendMessages = true,
                    onTextChange = {},
                    onSend = {},
                    onStop = {},
                    onRemoveAttachment = {},
                    onAttachImage = {},
                    agentId = state.agentId,
                    companionStatus = { LegacyThinkingToken(state, leadingInset = false) },
                )
            }
        }
    }

    @Composable
    private fun LegacyList(state: ChatUiState, modifier: Modifier) {
        val cache = remember { IncrementalChatRenderItemsCache() }
        val items = remember(state) {
            cache.renderItems(state.messages, ChatDisplayMode.Interactive, state.messageListChange, state.agentId)
        }
        ChatMessageList(
            state = state,
            renderItems = items,
            chatMode = "interactive",
            scrollToMessageId = null,
            activeFontScale = 1f,
            onActiveFontScaleChange = {},
            onFontScaleChange = {},
            onLoadOlderMessages = {},
            onSendMessage = {},
            onRerunMessage = {},
            onSubmitApproval = { _, _, _, _ -> },
            onToggleRunCollapsed = {},
            onToggleReasoningExpanded = {},
            onAttachmentImageTap = null,
            modifier = modifier,
        )
    }

    @Composable
    private fun LegacyThinkingToken(state: ChatUiState, leadingInset: Boolean) {
        ThinkingTextToken(
            visible = true,
            textOverride = activeRunActivity(state.messages).label(0L),
            reducedMotion = true,
            contentPadding = PaddingValues(
                start = if (leadingInset) LettaDimens.Space.lg else 0.dp,
                end = LettaDimens.Space.lg,
                top = LettaDimens.Space.xs,
                bottom = LettaDimens.Space.xs,
            ),
        )
    }

    /** The shared page as Android hosts it (SharedChatPage): full screen, Touch idiom. */
    @Composable
    private fun SharedPage(state: ChatUiState) {
        val port = remember(state) { FixturePort(state) }
        ChatSurface(
            port = port,
            presentation = ChatSurfacePresentation.ChatFirst,
            onIntent = {},
            host = ChatSurfaceHost(),
            appearance = AndroidAppearance,
            modifier = Modifier.fillMaxSize(),
        )
    }

    private class FixturePort(state: ChatUiState) : ChatSessionPort {
        override val uiState: StateFlow<ChatUiState> = MutableStateFlow(state)
        override val composer: StateFlow<ChatComposerUiState> = MutableStateFlow(ChatComposerUiState(canSend = true))
        override val actions: ChatActions = NoOpChatActions
    }

    private fun outputDir(): File =
        File(System.getProperty(OUTPUT_PROPERTY) ?: DEFAULT_OUTPUT).apply { mkdirs() }

    private companion object {
        const val ENABLED_PROPERTY = "letta.rowParity"
        const val OUTPUT_PROPERTY = "letta.rowParity.dir"
        const val DEFAULT_OUTPUT = "build/outputs/roborazzi/row-parity"
        const val SETTLE_MILLIS = 1_500L
        const val SETTLE_FRAMES = 3
        const val MAX_NOTE = 160

        /** SharedChatPage's appearance: tool calls open in a sheet, the phone's Touch idiom. */
        val AndroidAppearance = ChatSurfaceAppearance(
            toolDetails = ChatToolDetails.Sheet,
            platformStyle = ChatPlatformStyle.Touch,
        )
    }
}
