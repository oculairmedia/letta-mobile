package com.letta.mobile.data.settings

import com.letta.mobile.data.controller.reconnect.summary
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.data.notify.AttentionPrefsRepository
import com.letta.mobile.data.runtime.PermissionModeSettings
import com.letta.mobile.data.transport.appserver.AppServerProbeResult
import kotlinx.coroutines.flow.StateFlow

/*
 * letta-mobile-ckmub.3.12 (S10, P9): the small platform-service ports behind the shared Settings
 * page's standard sections. Each platform binds the ones it has (a null port hides its rows); the
 * page draws the same rows for every platform from them.
 */

/** One stored setting: its current value and how to change it. */
class SettingValue<T>(val current: StateFlow<T>, private val write: (T) -> Unit) {
    fun set(value: T) = write(value)
}

/** Appearance: the theme and its preset, dynamic colour where the OS has it, and haptics. */
class AppearanceSettings(
    val theme: SettingValue<AppTheme>? = null,
    val themePreset: SettingValue<ThemePreset>? = null,
    /** Null where the OS offers no wallpaper colours (desktop, Android before 12). */
    val dynamicColor: SettingValue<Boolean>? = null,
    val haptics: SettingValue<Boolean>? = null,
) {
    /** True when the platform binds none of these, so the page leaves the section out. */
    val isEmpty: Boolean get() = listOfNotNull(theme, themePreset, dynamicColor, haptics).isEmpty()
}

/**
 * Chat: the shared chat page switch (kept for one release, letta-mobile-ckmub S4/S9), whether a
 * conversation opens on the canvas, and the default permission mode new conversations start in.
 */
class ChatSettings(
    val sharedChatPage: SettingValue<Boolean>? = null,
    /** Why the shared chat page switch cannot change (the launch forces it on), or null. */
    val sharedChatPageLockedReason: String? = null,
    val openChatsOnCanvas: SettingValue<Boolean>? = null,
    val permissionMode: PermissionModeSettings? = null,
) {
    /** True when the platform binds none of these, so the page leaves the section out. */
    val isEmpty: Boolean get() = listOfNotNull(sharedChatPage, openChatsOnCanvas, permissionMode).isEmpty()
}

/** Notifications: the app-wide approval notification switch. Platform rows come as contributions. */
class NotificationSettings(val attention: AttentionPrefsRepository? = null)

/** Probes an App Server address before it is saved, without opening a session. */
fun interface BackendConnectionTester {
    suspend fun test(serverUrl: String, accessToken: String?): SettingsStatus
}

/** What a connection probe's result says on the Settings page; null means the address cannot be probed. */
fun AppServerProbeResult?.toSettingsStatus(): SettingsStatus = when (this) {
    null -> SettingsStatus(
        "Test connection checks ws://, wss:// and http(s):// App Servers. Iroh endpoints are checked when you connect.",
    )
    is AppServerProbeResult.Ok -> SettingsStatus(summary(), SettingsTone.Positive)
    else -> SettingsStatus(summary(), SettingsTone.Error)
}
