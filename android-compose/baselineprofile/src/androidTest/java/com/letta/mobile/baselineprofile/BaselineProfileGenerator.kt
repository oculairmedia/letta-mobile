package com.letta.mobile.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates a baseline profile for letta-mobile by walking the hot
 * user path:
 *
 *   1. Cold launch
 *   2. Wait for the chat/admin list to compose
 *   3. Scroll the list
 *   4. Tap the first row to open a chat (if available)
 *   5. Scroll the chat timeline
 *
 * Steps that don't find their target are skipped rather than failing —
 * a device with no saved server or no chats should still produce a
 * valid cold-start profile. Richer profiles require a device primed
 * with a real chat history (see the `benchmark` build type in :app).
 *
 * Run with:
 *
 *     ./gradlew :app:generateBenchmarkBaselineProfile
 *
 * The generated profile lands at
 *   :app/src/benchmark/generated/baselineProfiles/
 * and is bundled into release APKs by the androidx.baselineprofile
 * plugin.
 *
 * See letta-mobile-o7ob.2.1.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = TARGET_PACKAGE) {
        startActivityAndWait()

        // Wait for the first frame of the top-level list.
        device.wait(
            Until.hasObject(By.pkg(TARGET_PACKAGE).depth(0)),
            STARTUP_WAIT_MS,
        )

        // Scroll the outer list a few times so compose inflates the
        // hot scroll path classes.
        val list = device.findObject(By.scrollable(true))
        if (list != null) {
            list.setGestureMargin(device.displayWidth / 5)
            repeat(SCROLL_PASSES) {
                list.fling(Direction.DOWN)
                device.waitForIdle(IDLE_MS)
            }
            list.fling(Direction.UP)
            device.waitForIdle(IDLE_MS)
        }

        // Try to drill into the first conversation. Any clickable item
        // with accessible text works; we don't depend on specific tags.
        val firstRow = device.findObject(By.clickable(true))
        if (firstRow != null) {
            firstRow.click()
            device.waitForIdle(IDLE_MS)

            // Scroll the chat timeline if we landed on one.
            val timeline = device.findObject(By.scrollable(true))
            if (timeline != null) {
                timeline.setGestureMargin(device.displayWidth / 5)
                repeat(SCROLL_PASSES) {
                    timeline.fling(Direction.UP)
                    device.waitForIdle(IDLE_MS)
                }
            }
            device.pressBack()
            device.waitForIdle(IDLE_MS)
        }
    }

    private companion object {
        // Baseline profile runs against the `benchmark` buildType of :app
        // which uses the `.benchmark` applicationIdSuffix.
        const val TARGET_PACKAGE = "com.letta.mobile.benchmark"
        const val STARTUP_WAIT_MS = 10_000L
        const val SCROLL_PASSES = 3
        const val IDLE_MS = 500L
    }
}
