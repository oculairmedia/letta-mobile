package com.letta.mobile.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.By
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measures cold / warm / hot startup of letta-mobile.
 *
 * Run with:
 *
 *     ./gradlew :macrobenchmark:connectedBenchmarkAndroidTest
 *
 * CSV results land under
 *   android-compose/macrobenchmark/build/outputs/connected_android_test_additional_output/.
 *
 * CompilationMode variants we care about:
 *   - None(): worst-case, no AOT
 *   - Partial(): mirrors a real Play Store install with baseline profile
 *   - Full(): upper bound after DEX layout + R8 have done their work
 *
 * StartupMode variants:
 *   - COLD: process just started (most user-visible)
 *   - WARM: activity restart
 *   - HOT: activity resume
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartupCompilationNone() = startup(StartupMode.COLD, CompilationMode.None())

    @Test
    fun coldStartupCompilationPartial() = startup(StartupMode.COLD, CompilationMode.Partial())

    @Test
    fun warmStartup() = startup(StartupMode.WARM, CompilationMode.Partial())

    @Test
    fun hotStartup() = startup(StartupMode.HOT, CompilationMode.Partial())

    private fun startup(startupMode: StartupMode, compilationMode: CompilationMode) {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(StartupTimingMetric()),
            iterations = DEFAULT_ITERATIONS,
            startupMode = startupMode,
            compilationMode = compilationMode,
            setupBlock = { pressHome() },
        ) {
            startActivityAndWait()

            // Wait for first composed content to be drawn. We use the
            // window's decor view coming up as the signal rather than a
            // specific compose tag to avoid coupling benchmarks to UI
            // structure. The Scaffold renders immediately after splash.
            device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE).depth(0)), STARTUP_WAIT_MS)
        }
    }

    private companion object {
        // App is built with `applicationIdSuffix = ".benchmark"` in the
        // :app `benchmark` buildType so dev and benchmark installs can
        // coexist on the same device. Macrobenchmark must target the
        // suffixed package — not the production one.
        const val TARGET_PACKAGE = "com.letta.mobile.benchmark"
        const val DEFAULT_ITERATIONS = 5
        const val STARTUP_WAIT_MS = 10_000L
    }
}
