package com.letta.mobile.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice

internal object ProfileTarget {
    private const val DEFAULT_PACKAGE_NAME = "com.letta.mobile.benchmark"
    private const val TARGET_PACKAGE_ARG = "androidx.benchmark.targetPackageName"
    private const val AUTOMATION_PAYLOAD_ARG = "automationConfigPayloadBase64"
    private const val AUTOMATION_PAYLOAD_EXTRA = "com.letta.mobile.extra.AUTOMATION_CONFIG_PAYLOAD_BASE64"
    const val STARTUP_WAIT_MS = 10_000L
    private const val PROFILE_FLUSH_WAIT_MS = 5_000L

    fun targetPackageName(): String = InstrumentationRegistry.getArguments()
        .getString(TARGET_PACKAGE_ARG)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: DEFAULT_PACKAGE_NAME

    /**
     * Macrobenchmark confirms startup by reading the target activity's
     * framestats. If the device is sleeping or showing keyguard/notification
     * shade, ActivityTaskManager can report the launch as displayed while the
     * app window stays hidden, leaving framestats empty. Keep the device in a
     * normal home state before every profile capture.
     */
    @android.annotation.SuppressLint("DiscouragedApi")
    fun prepareDevice(device: UiDevice) {
        if (!device.isScreenOn) {
            device.wakeUp()
        }
        device.executeShellCommand("wm dismiss-keyguard")
        device.pressHome()
        device.waitForIdle()
    }

    /**
     * Keep profile generation deterministic by dismissing optional runtime
     * permission prompts before the first measured launch. Android 13+
     * notification permission is the known prompt for benchmark installs; the
     * shell command is intentionally best-effort so older devices keep working.
     */
    @android.annotation.SuppressLint("DiscouragedApi")
    fun grantOptionalRuntimePermissions(device: UiDevice) {
        device.executeShellCommand("pm grant ${targetPackageName()} android.permission.POST_NOTIFICATIONS")
    }

    @android.annotation.SuppressLint("DiscouragedApi")
    fun startActivityAndWait(scope: MacrobenchmarkScope) {
        prepareDevice(scope.device)
        val automationPayload = InstrumentationRegistry.getArguments()
            .getString(AUTOMATION_PAYLOAD_ARG)
            ?.trim()
            .orEmpty()
        val payloadExtra = automationPayload
            .takeIf { it.isNotBlank() }
            ?.let { " --es $AUTOMATION_PAYLOAD_EXTRA ${it.shellQuote()}" }
            .orEmpty()
        val targetPackage = targetPackageName()
        scope.device.executeShellCommand(
            "am start -W " +
                "-a android.intent.action.MAIN " +
                "-c android.intent.category.LAUNCHER " +
                "-f 0x10008000 " +
                "-n $targetPackage/.MainActivity" +
                payloadExtra,
        )
        scope.device.waitForIdle(STARTUP_WAIT_MS)
    }

    fun waitForProfileFlush(device: UiDevice) {
        device.waitForIdle(PROFILE_FLUSH_WAIT_MS)
    }

    private fun String.shellQuote(): String = "'${replace("'", "'\\''")}'"
}
