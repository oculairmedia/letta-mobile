package com.letta.mobile.feature.chat.parity

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.feature.chat.NoOpChatActions
import com.letta.mobile.feature.chat.screen.shared.SharedChatPageLocals
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
import com.letta.mobile.ui.chat.surface.composer.ComposerThinkingSnapshot
import com.letta.mobile.ui.chat.surface.timeline.ChatTimelineSnapshot
import com.letta.mobile.ui.theme.LettaTheme
import java.io.File
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
 * The chat rows' golden screenshots (hard cut S9, letta-mobile-ckmub.2.2). Until S9 this harness drew
 * every row type side by side through the legacy Android timeline and the shared one (ckmub.1, S4
 * brought the shared rows to parity, 0.03% on the whole timeline); the legacy rows are gone, so the
 * shared rendering S4 accepted is now the golden.
 *
 * Every row type in [ParityRow.all] is drawn from [RowParityFixture] through the SHARED timeline
 * (sharedUI ChatTimelineSnapshot -> ChatRow*; the ChatSurface page for the mascot seat) at phone
 * width, light and dark, and compared with the committed golden
 * `src/test/snapshots/rows/<theme>/<row>.png`. It writes `<theme>/<row>.{golden,shared,diff,
 * side-by-side}.png` and `report.md` (row type -> pixel diff %) under build/outputs/roborazzi/row-parity,
 * and fails when a row moves by more than [MAX_DIFF_PERCENT] (or nothing renders). A row with no
 * golden yet records one; `letta.rowGolden.record=true` re-records every golden.
 *
 * Off by default so the regular unit test run stays fast; run it with
 * `./gradlew --no-daemon :feature-chat:rowGoldenReport` (add `-Pletta.rowGolden.record=true` to record).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, qualifiers = "w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Tag("screenshot")
class RowParityScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun onlyWhenAsked() {
        assumeTrue("row goldens: run :feature-chat:rowGoldenReport", System.getProperty(ENABLED_PROPERTY) == "true")
    }

    @Test
    fun light() = runAll(AppTheme.LIGHT, "light")

    @Test
    fun dark() = runAll(AppTheme.DARK, "dark")

    private fun runAll(theme: AppTheme, themeName: String) {
        var target by mutableStateOf<ParityRow?>(null)
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
        val output = ThemeOutput(themeName, dir.resolve(themeName), goldenDir().resolve(themeName))
        val results = ParityRow.all.map { row ->
            runCatching {
                val shared = capture { target = row }
                record(output, row, shared)
            }.getOrElse { failure ->
                ParityResult(themeName, row.id, row.label, null, null, null, "render failed: ${failure::class.simpleName}: ${failure.message?.take(MAX_NOTE)}")
            }
        }
        target = null
        RowParityReport.writeResults(dir, themeName, results)
        val report = RowParityReport.rebuild(dir, ParityRow.all.map { it.id })
        println(report)
        assertTrue("no row rendered:\n$report", results.any { it.diffPercent != null })
        val moved = results.filter { (it.diffPercent ?: 0.0) > MAX_DIFF_PERCENT }
        assertTrue("rows moved from their golden by more than $MAX_DIFF_PERCENT%:\n$report", moved.isEmpty())
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
        return captureWhenStable()
    }

    /**
     * Markdown parses off the main thread on real time, not the manual clock, so a row can still show
     * its plain-text placeholder (or nothing) on the first grab. Grab until two consecutive frames
     * agree (pages whose mascot never stops animating give up after [STABLE_ATTEMPTS]).
     */
    private fun captureWhenStable(): Pixels {
        var previous = grab()
        repeat(STABLE_ATTEMPTS) { attempt ->
            Thread.sleep(STABLE_WAIT_MILLIS)
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
            val next = grab()
            if (attempt >= MIN_ATTEMPTS && next.argb.contentEquals(previous.argb)) return next
            previous = next
        }
        return previous
    }

    private fun grab(): Pixels = Pixels.of(composeRule.onRoot().captureToImage().asAndroidBitmap())

    /** Where one theme's images go, where its goldens live, and the theme's name in the report. */
    private class ThemeOutput(val name: String, val dir: File, val goldens: File)

    private fun record(output: ThemeOutput, row: ParityRow, raw: Pixels): ParityResult {
        val shared = RowParityImages.cropToContent(raw)
        val goldenFile = output.goldens.resolve("${row.id}.png")
        val recording = System.getProperty(RECORD_PROPERTY) == "true" || !goldenFile.exists()
        if (recording) RowParityImages.write(shared, goldenFile)
        val golden = Pixels.of(BitmapFactory.decodeFile(goldenFile.absolutePath))
        val result = RowParityImages.compare(golden, shared)
        val images = mapOf(
            "golden" to result.golden,
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
            goldenHeightDp = (result.summary.goldenHeightPx / density).toInt(),
            sharedHeightDp = (result.summary.sharedHeightPx / density).toInt(),
            note = if (recording) "golden recorded" else "",
        )
    }

    @Composable
    private fun Draw(row: ParityRow) {
        val state = row.state
        when (row.layout) {
            ParityLayout.TIMELINE, ParityLayout.THINKING -> SharedChatPageLocals(hapticsEnabled = false) {
                // The shared page's composer carries the thinking token.
                Column(Modifier.fillMaxSize()) {
                    ChatTimelineSnapshot(
                        state = state,
                        actions = NoOpChatActions,
                        appearance = AndroidAppearance,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    if (row.layout == ParityLayout.THINKING) ComposerThinkingSnapshot(state, Modifier.fillMaxWidth())
                }
            }
            ParityLayout.THINKING_MASCOT -> WithParityMascot(RowParityFixture.AGENT_ID) {
                SharedChatPageLocals(hapticsEnabled = false) { SharedPage(state) }
            }
        }
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

    private fun goldenDir(): File = File(System.getProperty(GOLDEN_PROPERTY) ?: DEFAULT_GOLDENS)

    private companion object {
        const val ENABLED_PROPERTY = "letta.rowParity"
        const val RECORD_PROPERTY = "letta.rowGolden.record"
        const val OUTPUT_PROPERTY = "letta.rowParity.dir"
        const val GOLDEN_PROPERTY = "letta.rowGolden.dir"
        const val DEFAULT_OUTPUT = "build/outputs/roborazzi/row-parity"
        const val DEFAULT_GOLDENS = "src/test/snapshots/rows"
        const val MAX_DIFF_PERCENT = 2.0
        const val SETTLE_MILLIS = 1_500L
        const val SETTLE_FRAMES = 3
        const val STABLE_ATTEMPTS = 12
        const val MIN_ATTEMPTS = 3
        const val STABLE_WAIT_MILLIS = 60L
        const val MAX_NOTE = 160

        /** SharedChatPage's appearance: tool calls open in a sheet, the phone's Touch idiom. */
        val AndroidAppearance = ChatSurfaceAppearance(
            toolDetails = ChatToolDetails.Sheet,
            platformStyle = ChatPlatformStyle.Touch,
        )
    }
}
