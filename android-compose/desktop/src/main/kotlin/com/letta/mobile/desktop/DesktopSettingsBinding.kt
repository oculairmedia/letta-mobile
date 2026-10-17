package com.letta.mobile.desktop

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.ui.shell.pages.channels.ChannelsPageSlots
import com.letta.mobile.ui.shell.pages.channels.ChannelsRefreshActionSlot
import com.letta.mobile.ui.shell.pages.channels.ChannelsSearchFieldSlot

/** What the desktop's Settings services read: the backend, the bundled runtime, Nucleus and pairing. */
internal data class DesktopSettingsInputs(
    val state: DesktopBootstrapState,
    /** Agent-rail recency window in days; 0 shows every agent. */
    val railRecencyDays: Int = RAIL_RECENCY_DAYS_DEFAULT,
    val nucleus: DesktopNucleusState,
    val localRuntimeProvider: DesktopLocalRuntimeProviderState,
    val localBackendDirectory: DesktopLocalBackendDirectoryState,
    val localRuntime: DesktopLocalRuntimeController,
    val pairing: com.letta.mobile.ui.pairing.InstallAndPairBindings? = null, // dpv7m.3 install-and-pair card
)

internal data class DestinationNucleusActions(
    val onCheckForUpdates: () -> Unit,
    val onDownloadUpdate: () -> Unit,
    val onInstallUpdate: () -> Unit,
    val onRefreshSystemInfo: () -> Unit,
    val onAutoLaunchChanged: (Boolean) -> Unit,
    val onOpenAutoLaunchSettings: () -> Unit,
    val onTestNotification: () -> Unit,
)

/** What the desktop's Settings services do. */
internal data class DesktopSettingsActions(
    val onConfigSaved: (LettaConfig) -> Unit,
    val onTokenCleared: () -> Unit,
    val onIrohIdentityReset: () -> Unit,
    val onRailRecencyDaysChange: (Int) -> Unit = {},
    /** Settings' Providers section opens the Providers destination. */
    val onOpenProviders: () -> Unit = {},
    /** Settings' Plugins section opens the Plugins destination (letta-mobile-cdgpr.9). */
    val onOpenPlugins: () -> Unit = {},
    /** Whether the host runs plugins; the Plugins section is hidden while it does not (letta-mobile-cdgpr.8). */
    val pluginViews: com.letta.mobile.data.plugin.view.PluginViewsModel? = null,
    val nucleus: DestinationNucleusActions,
    val localRuntimeProvider: DesktopLocalRuntimeProviderActions,
    val localBackendDirectory: DesktopLocalBackendDirectoryActions,
)

/** The desktop's compact Jewel search field and icon-only refresh in the shared pages' headers. */
internal val DesktopChannelsPageSlots = ChannelsPageSlots(
    searchField = ChannelsSearchFieldSlot { query, onQueryChange, placeholder, modifier ->
        DesktopTextField(value = query, onValueChange = onQueryChange, placeholder = placeholder, modifier = modifier)
    },
    refreshAction = ChannelsRefreshActionSlot { onRefresh, modifier ->
        DesktopIconButton(imageVector = Icons.Outlined.Refresh, contentDescription = "Refresh", onClick = onRefresh, modifier = modifier)
    },
)
