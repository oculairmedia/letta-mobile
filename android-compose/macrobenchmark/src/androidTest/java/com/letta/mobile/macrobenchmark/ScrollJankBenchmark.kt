package com.letta.mobile.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measures scroll jank on the chat list / admin list screens via
 * [FrameTimingMetric].
 *
 * Run with:
 *
 *     ./gradlew :macrobenchmark:connectedBenchmarkAndroidTest \
 *         -Pandroid.testInstrumentationRunnerArguments.class=\
 *           com.letta.mobile.macrobenchmark.ScrollJankBenchmark
 *
 * Outputs per-frame durations and percentile buckets (p50/p90/p95/p99)
 * which [perf/baselines.json](../../../perf/baselines.json) regresses
 * against in CI (see letta-mobile-o7ob.4.1).
 */
@RunWith(AndroidJUnit4::class)
class ScrollJankBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun scrollChatListCompilationNone() = scroll(CompilationMode.None())

    @Test
    fun scrollChatListCompilationPartial() = scroll(CompilationMode.Partial())

    private fun scroll(compilationMode: CompilationMode) {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            iterations = DEFAULT_ITERATIONS,
            startupMode = StartupMode.WARM,
            compilationMode = compilationMode,
            setupBlock = {
                startActivityAndWait()
                // Wait for the list to be drawn before starting
                // measurement — otherwise jank from first-frame setup
                // leaks into the scroll run.
                device.wait(
                    Until.hasObject(By.pkg(TARGET_PACKAGE).depth(0)),
                    STARTUP_WAIT_MS,
                )
            },
        ) {
            // Grab the scrollable container. `scrollable = true` is what
            // every LazyColumn / LazyList reports to the a11y tree, so we
            // don't need to attach a specific tag.
            val list = device.findObject(By.scrollable(true))
            if (list != null) {
                list.setGestureMargin(device.displayWidth / 5)
                repeat(SCROLL_PASSES) {
                    list.fling(Direction.DOWN)
                    device.waitForIdle(SCROLL_IDLE_MS)
                }
                repeat(SCROLL_PASSES) {
                    list.fling(Direction.UP)
                    device.waitForIdle(SCROLL_IDLE_MS)
                }
            }
        }
    }

    private companion object {
        const val TARGET_PACKAGE = "com.letta.mobile.benchmark"
        const val DEFAULT_ITERATIONS = 5
        const val STARTUP_WAIT_MS = 10_000L
        const val SCROLL_PASSES = 3
        const val SCROLL_IDLE_MS = 500L
    }
}
