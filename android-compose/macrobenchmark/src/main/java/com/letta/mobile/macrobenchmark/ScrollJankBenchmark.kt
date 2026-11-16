package com.letta.mobile.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.UiObject2
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
 * Requires a seeded conversation selected by instrumentation arguments.
 * These frame metrics are not currently gated in CI.
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
                launchSeededConversation()
            },
        ) {
            // Grab the scrollable container. `scrollable = true` is what
            // every LazyColumn / LazyList reports to the a11y tree, so we
            // don't need to attach a specific tag.
            val list = checkNotNull(device.findObject(By.scrollable(true))) { "Timeline list was not found" }
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

    private companion object {
        const val TARGET_PACKAGE = "ca.oculair.meridian.benchmark"
        const val DEFAULT_ITERATIONS = 5
        const val SCROLL_PASSES = 3
        const val SCROLL_IDLE_MS = 500L
    }
}

internal fun benchmarkArgument(name: String): String =
    checkNotNull(InstrumentationRegistry.getArguments().getString(name)?.takeIf { it.isNotBlank() }) {
        "Missing instrumentation argument: $name"
    }

internal fun MacrobenchmarkScope.launchSeededConversation(): UiObject2 {
    device.executeShellCommand("pm grant ca.oculair.meridian.benchmark android.permission.RECORD_AUDIO")
    startActivityAndWait {
        it.putExtra("com.letta.mobile.extra.AUTOMATION_CONFIG_PAYLOAD_BASE64", benchmarkArgument("configPayload"))
        it.putExtra("notification_agent_id", benchmarkArgument("agentId"))
        it.putExtra("notification_conversation_id", benchmarkArgument("conversationId"))
    }
    if (!device.wait(Until.hasObject(By.text("Type a message…")), NAVIGATION_WAIT_MS)) {
        val title = benchmarkArgument("conversationTitle")
        val rows = device.findObjects(By.text(title))
        val index = benchmarkArgument("conversationIndex").toInt()
        check(index in rows.indices) { "Conversation row $index named $title was not found" }
        rows[index].click()
    }
    device.wait(Until.findObject(By.text("Deny")), PERMISSION_WAIT_MS)?.click()
    check(device.wait(Until.hasObject(By.text("Type a message…")), CONVERSATION_WAIT_MS)) {
        "Chat timeline composer was not found"
    }
    val marker = benchmarkArgument("conversationMarker")
    check(device.wait(Until.hasObject(By.textContains(marker)), CONVERSATION_WAIT_MS)) {
        "Expected seeded timeline marker was not found"
    }
    return checkNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), CONVERSATION_WAIT_MS)) {
        "Seeded conversation composer was not found"
    }
}

private const val CONVERSATION_WAIT_MS = 30_000L

private const val NAVIGATION_WAIT_MS = 30_000L
private const val PERMISSION_WAIT_MS = 3_000L
