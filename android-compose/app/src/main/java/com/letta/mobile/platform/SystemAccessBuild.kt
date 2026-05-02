package com.letta.mobile.platform

import com.letta.mobile.BuildConfig

/** Compile-time distribution flavor gates for Android system-access features. */
enum class SystemAccessFlavor {
    Play,
    Sideload,
    Root,
}

/**
 * Single app-facing view of system-access compile-time gates.
 *
 * These flags intentionally come from Gradle product flavors rather than remote
 * config or runtime toggles so Play artifacts do not expose sideload/root-only
 * tools by accident. Future capability registry code should combine this build
 * posture with runtime permission/manifest probes and fail closed when a
 * capability is unavailable.
 */
object SystemAccessBuild {
    val flavor: SystemAccessFlavor = when (BuildConfig.SYSTEM_ACCESS_FLAVOR) {
        "play" -> SystemAccessFlavor.Play
        "sideload" -> SystemAccessFlavor.Sideload
        "root" -> SystemAccessFlavor.Root
        else -> SystemAccessFlavor.Play
    }

    val localShellEnabled: Boolean = BuildConfig.ENABLE_LOCAL_SHELL
    val shizukuEnabled: Boolean = BuildConfig.ENABLE_SHIZUKU
    val rootToolsEnabled: Boolean = BuildConfig.ENABLE_ROOT_TOOLS

    val privilegedToolsEnabled: Boolean
        get() = localShellEnabled || shizukuEnabled || rootToolsEnabled
}
