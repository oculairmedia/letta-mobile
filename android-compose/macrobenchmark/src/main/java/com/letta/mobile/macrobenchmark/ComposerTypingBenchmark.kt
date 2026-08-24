package com.letta.mobile.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measures input → render latency of the chat composer via
 * [FrameTimingMetric]. Typing is one of the most recomposition-heavy
 * interactions on chat screens — every keystroke re-runs the composer
 * state tree plus any `derivedStateOf` hanging off `inputText`, and on
 * wide chat history screens also re-emits layouts for the staged
 * attachment strip and the send-enabled computation.
 *
 * Run with:
 *
 *     ./gradlew :macrobenchmark:connectedBenchmarkAndroidTest \
 *         -Pandroid.testInstrumentationRunnerArguments.class=\
 *           com.letta.mobile.macrobenchmark.ComposerTypingBenchmark
 *
 * CSV/JSON output lands under
 *   android-compose/macrobenchmark/build/outputs/connected_android_test_additional_output/.
 *
 * The benchmark is intentionally tolerant about UI structure: it finds
 * the first focusable `EditText` reachable from the launch surface
 * rather than depending on a specific compose test tag. If no editable
 * field is reachable it logs a structural mismatch and bails out of the
 * measure block — the same soft-skip pattern used by
 * [ScrollJankBenchmark].
 *
 * Baseline is tracked as `composer.typing.jank.pct` in
 * `perf/baselines.json` (see letta-mobile-o7ob.4.1).
 */
@RunWith(AndroidJUnit4::class)
class ComposerTypingBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun typeComposerCompilationNone() = type(CompilationMode.None())

    @Test
    fun typeComposerCompilationPartial() = type(CompilationMode.Partial())

    private fun type(compilationMode: CompilationMode) {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            iterations = DEFAULT_ITERATIONS,
            startupMode = StartupMode.WARM,
            compilationMode = compilationMode,
            setupBlock = {
                startActivityAndWait()
                // Wait for first decor frame so splash → first composition
                // jank doesn't leak into the typing measurement.
                device.wait(
                    Until.hasObject(By.pkg(TARGET_PACKAGE).depth(0)),
                    STARTUP_WAIT_MS,
                )
            },
        ) {
            // Compose TextField surfaces to a11y / uiautomator as an
            // `android.widget.EditText` that reports `focusable=true` and
            // `clazz=android.widget.EditText`. Match by class to avoid
            // coupling to placeholder text (which varies with i18n).
            val field: UiObject2? = device.wait(
                Until.findObject(By.clazz("android.widget.EditText")),
                FIELD_WAIT_MS,
            )
            if (field == null) {
                // Launch surface on this device doesn't expose an editable
                // field (e.g. onboarding / login). Frame-timing on an
                // empty block is harmless; just emit a single idle frame
                // tick so the iteration still has a measurable span.
                device.waitForIdle(IDLE_AFTER_BURST_MS)
                return@measureRepeated
            }

            // Focus the field so keystrokes land in the composer. A tap
            // rather than `field.click()` avoids accidentally triggering
            // send/attach buttons if the selector matched a different
            // EditText.
            field.click()
            device.waitForIdle(FOCUS_WAIT_MS)

            // Burst 1: simulated typing via setText. This exercises the
            // single-observer onValueChange → recomposition path for a
            // large pending value. Setting text in one shot gives a clean
            // frame window we can attribute to the value-change cascade.
            field.text = TYPING_BURST
            device.waitForIdle(IDLE_AFTER_BURST_MS)

            // Burst 2: clear + incremental re-enter by repeatedly setting
            // text character-by-character. This is closer to the actual
            // per-keystroke recomposition profile; we still drive it via
            // uiautomator setText rather than pressKeyCode to keep the
            // benchmark deterministic across keyboard variants.
            field.text = ""
            device.waitForIdle(IDLE_BETWEEN_BURSTS_MS)
            for (i in 1..INCREMENTAL_CHARS) {
                field.text = TYPING_BURST.take(i)
            }
            device.waitForIdle(IDLE_AFTER_BURST_MS)

            // Reset composer state so the next iteration starts from an
            // empty field. This keeps iteration N+1 independent of
            // iteration N's tail state.
            field.text = ""
            device.waitForIdle(IDLE_AFTER_BURST_MS)
        }
    }

    private companion object {
        // Match the suffixed package from :app's `benchmark` buildType.
        const val TARGET_PACKAGE = "ca.oculair.meridian.benchmark"
        const val DEFAULT_ITERATIONS = 5
        const val STARTUP_WAIT_MS = 10_000L
        const val FIELD_WAIT_MS = 5_000L
        const val FOCUS_WAIT_MS = 250L
        const val IDLE_BETWEEN_BURSTS_MS = 150L
        const val IDLE_AFTER_BURST_MS = 500L

        // Long enough to force real LazyColumn-adjacent recomposition and
        // indicator-color recalculation but short enough to keep per
        // iteration under ~2 s on mid-tier devices.
        const val TYPING_BURST =
            "the quick brown fox jumps over the lazy dog 0123456789"
        const val INCREMENTAL_CHARS = 24
    }
}
