package com.letta.mobile.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import android.content.Intent
import androidx.benchmark.macro.ExperimentalMacrobenchmarkApi
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.Metric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.benchmark.traceprocessor.TraceProcessor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Where the time goes when a conversation's timeline opens, stage by stage.
 *
 * The app marks each stage as a zero-length trace section `TimelineOpen/<stage>` (and a telemetry
 * event of the same name) under one open id; see `TimelineOpenTrace` in `:sharedLogic`. [StageMetric]
 * reads those sections out of the Perfetto trace and reports every stage as milliseconds since that
 * open's `open.requested`, so each iteration yields one sample per stage and the JSON output carries
 * the per-iteration values the p50 / p90 are computed from.
 *
 * Two variants, each [DEFAULT_ITERATIONS] iterations:
 *  - cold: the process is killed before every iteration, the app is relaunched, and the conversation
 *    is opened from the conversation list;
 *  - warm: the process and its in-memory state stay alive, and the conversation is re-opened from the
 *    list after the previous iteration left it.
 *
 * A third test, [openByLaunchCold], opens the conversation by launching straight into it with a cold
 * process and so also reports [StartupTimingMetric]'s time to initial display (the in-app list click
 * above is not an activity start, so the startup metric does not apply to it).
 *
 * The app under test is the root-debug dev build (`com.letta.mobile.dev`, debuggable, already signed
 * in on the device): this benchmark never installs a different build or touches a different package.
 * Compilation is left exactly as installed ([CompilationMode.Ignore]).
 *
 * Run on the Pixel 2 XL against the already-installed dev app. The dev build is debuggable, so the
 * benchmark library's debuggable guard is suppressed; the numbers are therefore a debug build's, and
 * only their stage proportions (not absolute times) carry over to a release build:
 *
 *     adb -s 711KPAE0914240 shell am instrument -w \
 *         -e class com.letta.mobile.macrobenchmark.TimelineOpenBenchmark \
 *         -e androidx.benchmark.suppressErrors DEBUGGABLE,EMULATOR \
 *         -e targetPackage com.letta.mobile.dev \
 *         -e agentId ... -e conversationId ... -e conversationTitle ... \
 *         com.letta.mobile.macrobenchmark/androidx.test.runner.AndroidJUnitRunner
 *
 * Gradle's `connectedBenchmarkAndroidTest` uninstalls the test APK afterwards, so on a shared device
 * install the benchmark APK once and use `am instrument` as above.
 *
 * Needs a signed-in account with that real conversation: there is no fixture, and a missing
 * conversation fails the run instead of measuring nothing.
 */
@OptIn(ExperimentalMetricApi::class, ExperimentalMacrobenchmarkApi::class)
@RunWith(AndroidJUnit4::class)
class TimelineOpenBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun openFromListCold() = openFromList(cold = true)

    @Test
    fun openFromListWarm() = openFromList(cold = false)

    @Test
    fun openByLaunchCold() {
        benchmarkRule.measureRepeated(
            packageName = targetPackage(),
            metrics = listOf(StartupTimingMetric(), StageMetric()),
            iterations = DEFAULT_ITERATIONS,
            startupMode = StartupMode.COLD,
            compilationMode = CompilationMode.Ignore(),
            setupBlock = {
                enableOpenTrace()
                pressHome()
            },
        ) {
            startMainActivity {
                it.putExtra("notification_agent_id", benchmarkArgument("agentId"))
                it.putExtra("notification_conversation_id", benchmarkArgument("conversationId"))
            }
            awaitOpenSettled()
        }
    }

    private fun openFromList(cold: Boolean) {
        val title = benchmarkArgument("conversationTitle")
        benchmarkRule.measureRepeated(
            packageName = targetPackage(),
            metrics = listOf(StageMetric()),
            iterations = DEFAULT_ITERATIONS,
            // Process control is done in the setup block so cold and warm differ in exactly one thing.
            startupMode = null,
            compilationMode = CompilationMode.Ignore(),
            setupBlock = {
                enableOpenTrace()
                if (cold) killProcess()
                startMainActivity()
                showConversationList(title)
            },
        ) {
            armTraceSections()
            val rows = device.findObjects(By.text(title))
            val index = (InstrumentationArguments.optional("conversationIndex") ?: "0").toInt()
            check(index in rows.indices) { "Conversation row $index named $title was not found" }
            rows[index].click()
            awaitOpenSettled()
        }
    }

    /**
     * Launches `MainActivity` by name. The debug build also registers LeakCanary's launcher activity,
     * so the default launcher-intent lookup can start that screen instead of the app.
     */
    private fun MacrobenchmarkScope.startMainActivity(configure: (Intent) -> Unit = {}) {
        startActivityAndWait(Intent().setClassName(targetPackage(), MAIN_ACTIVITY).also(configure))
    }

    /**
     * Gives the app time to notice that tracing just started. An app process learns of a new trace
     * through a system property callback, so a section marked in the first moments of the measured
     * block is dropped from the trace; the stages are timed from `open.requested`, so this wait is
     * not part of what is reported.
     */
    private fun armTraceSections() = Thread.sleep(TRACE_ARM_MS)

    /** Makes the logcat/trace gate for the stage events true before the process under test starts. */
    private fun MacrobenchmarkScope.enableOpenTrace() {
        device.executeShellCommand("setprop log.tag.LettaTimelineOpenTrace VERBOSE")
    }

    /**
     * Brings [title]'s list row on screen. The app restores the last open conversation, so after a
     * launch (and after a previous iteration) the chat is showing: open the navigation drawer with
     * the chat header's "Agents and conversations" button and go Home, whose Recent list holds the
     * conversation rows.
     */
    private fun MacrobenchmarkScope.showConversationList(title: String) {
        if (!device.wait(Until.hasObject(By.text(title)), LIST_WAIT_MS)) {
            val opener = device.wait(Until.findObject(By.desc(LIST_OPENER_DESC)), CONVERSATION_WAIT_MS)
            checkNotNull(opener) { "The \"$LIST_OPENER_DESC\" button was not found" }.click()
            val home = device.wait(Until.findObject(By.desc(HOME_DESC)), LIST_WAIT_MS)
            checkNotNull(home) { "The drawer's \"$HOME_DESC\" entry was not found" }.click()
        }
        check(device.wait(Until.hasObject(By.text(title)), LIST_WAIT_MS)) {
            "Conversation \"$title\" is not listed; the device needs a signed-in account with that conversation"
        }
    }

    /**
     * Holds the measured block open until the conversation is up and the list has had time to take
     * its host page and go quiet, so the trace holds every stage. Not part of what is reported:
     * stages are read from the app's own marks, not from when this returns.
     */
    private fun MacrobenchmarkScope.awaitOpenSettled() {
        check(device.wait(Until.hasObject(By.text(COMPOSER_HINT)), CONVERSATION_WAIT_MS)) {
            "Chat timeline composer was not found"
        }
        device.waitForIdle(SETTLE_WINDOW_MS)
        Thread.sleep(SETTLE_WINDOW_MS)
    }

    private fun targetPackage(): String = InstrumentationArguments.optional("targetPackage") ?: DEV_PACKAGE

    /**
     * Each `TimelineOpen/<stage>` section after the last `open.requested` of the trace, in
     * milliseconds since that `open.requested`. Stages that did not occur in an iteration report
     * nothing for it, so a sample count below the iteration count means the stage was missed.
     */
    private class StageMetric : TraceMetric() {
        override fun getMeasurements(
            captureInfo: Metric.CaptureInfo,
            traceSession: TraceProcessor.Session,
        ): List<Metric.Measurement> {
            val names = STAGES.map { "$SECTION_PREFIX$it" }.toTypedArray()
            val slices = traceSession.querySlices(*names, packageName = captureInfo.targetPackageName)
            val start = slices.filter { it.name == "${SECTION_PREFIX}open.requested" }.maxOfOrNull { it.ts }
                ?: return emptyList()
            return STAGES.mapNotNull { stage ->
                val first = slices.filter { it.name == "$SECTION_PREFIX$stage" && it.ts >= start }.minOfOrNull { it.ts }
                first?.let { Metric.Measurement("timelineOpen_${stage.replace('.', '_')}Ms", (it - start) / NANOS_PER_MILLI) }
            }
        }
    }

    private companion object {
        const val DEV_PACKAGE = "com.letta.mobile.dev"
        const val MAIN_ACTIVITY = "com.letta.mobile.MainActivity"
        const val DEFAULT_ITERATIONS = 10
        const val SECTION_PREFIX = "TimelineOpen/"
        const val NANOS_PER_MILLI = 1_000_000.0
        const val COMPOSER_HINT = "Type a message…"
        const val LIST_WAIT_MS = 5_000L
        const val CONVERSATION_WAIT_MS = 30_000L
        const val SETTLE_WINDOW_MS = 3_000L
        const val TRACE_ARM_MS = 1_500L
        const val LIST_OPENER_DESC = "Agents and conversations"
        const val HOME_DESC = "Home"

        /** Wire names of `TimelineOpenTrace.Stage`, in open order. */
        val STAGES = listOf(
            "open.requested", "presentation.bound", "cache.read.start", "cache.read.end", "first.frame.with.rows",
            "network.page.start", "network.page.end", "reconcile.end", "settled",
        )
    }
}

/** Instrumentation arguments that may be absent. */
internal object InstrumentationArguments {
    fun optional(name: String): String? =
        androidx.test.platform.app.InstrumentationRegistry.getArguments().getString(name)?.takeIf { it.isNotBlank() }
}
