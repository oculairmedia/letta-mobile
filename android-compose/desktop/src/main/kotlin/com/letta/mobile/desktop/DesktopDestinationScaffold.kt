package com.letta.mobile.desktop

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.letta.mobile.data.channel.ChannelsPageActions
import com.letta.mobile.data.channel.ChannelsPageState
import com.letta.mobile.data.chat.runtime.ChatConnectionState
import com.letta.mobile.data.memory.graph.MemoryPageActions
import com.letta.mobile.data.memory.graph.MemoryPageState
import com.letta.mobile.data.memory.memfs.MemfsPageController
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.skills.SkillsCatalogState
import com.letta.mobile.data.tools.ToolLibraryState
import com.letta.mobile.ui.shell.pages.home.HomeConnectionPanel
import com.letta.mobile.ui.shell.pages.home.HomeConnectionState
import com.letta.mobile.desktop.chat.DesktopChatSurfaceState
import com.letta.mobile.desktop.home.DesktopHomeInputs
import com.letta.mobile.ui.shell.ShellAdminPages
import com.letta.mobile.ui.shell.ShellPageContent
import com.letta.mobile.ui.shell.ShellPageDeps
import com.letta.mobile.ui.shell.pages.channels.ChannelsPageOptions
import com.letta.mobile.ui.shell.pages.channels.ChannelsPageSlots
import com.letta.mobile.ui.shell.pages.channels.ChannelsRefreshActionSlot
import com.letta.mobile.ui.shell.pages.channels.ChannelsSearchFieldSlot
import com.letta.mobile.ui.shell.pages.home.HomePageCallbacks
import com.letta.mobile.ui.shell.pages.memory.MemoryWorkspaceBinding
import com.letta.mobile.ui.shell.pages.memory.rememberMemoryWorkspaceView
import com.letta.mobile.ui.shell.pages.schedules.SchedulesPageActions
import com.letta.mobile.ui.shell.pages.schedules.SchedulesPageState
import com.letta.mobile.ui.shell.pages.skills.SkillsPageActions
import com.letta.mobile.ui.shell.pages.skills.SkillsPageOptions
import com.letta.mobile.ui.shell.pages.skills.SkillsPageState
import com.letta.mobile.ui.shell.shellChannelsPage
import com.letta.mobile.ui.shell.shellHomePage
import com.letta.mobile.ui.shell.shellMemoryPage
import com.letta.mobile.ui.shell.shellSchedulesPage
import com.letta.mobile.ui.shell.shellSkillsPage

internal data class DestinationSkillsInputs(
    val catalog: SkillsCatalogState,
    val focusedAgentName: String?,
)

internal data class DestinationSkillsActions(
    val onRefresh: () -> Unit,
    val onInstall: (String) -> Unit,
    val onUninstall: (String) -> Unit,
    val onDismissActionError: () -> Unit,
)

internal data class DestinationToolsActions(
    val onRefresh: () -> Unit,
    val onSearchQueryChanged: (String) -> Unit,
    val onTagToggled: (String) -> Unit,
    val onClearTags: () -> Unit,
    val onLoadMore: () -> Unit,
)

internal data class DestinationContentInputs(
    val state: DesktopBootstrapState,
    /** Agent-rail recency window in days; 0 shows every agent. */
    val railRecencyDays: Int = RAIL_RECENCY_DAYS_DEFAULT,
    val home: DesktopHomeInputs,
    val chat: DesktopChatSurfaceState,
    val memoryState: MemoryPageState,
    val schedule: SchedulesPageState,
    val channels: ChannelsPageState,
    val toolLibraryState: ToolLibraryState,
    val skills: DestinationSkillsInputs,
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

internal data class DestinationContentActions(
    val home: HomePageCallbacks,
    val onRetryConnection: () -> Unit,
    val memory: MemoryPageActions,
    /** letta-mobile-bzvro.24: the Memory destination's Files view. */
    val memfs: MemfsPageController,
    val schedules: SchedulesPageActions,
    val channels: ChannelsPageActions,
    val tools: DestinationToolsActions,
    val skills: DestinationSkillsActions,
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

/** The desktop's own page containers for the shared switch: its chat page and the admin pages' bindings. */
internal data class DesktopHostPages(
    val chat: ShellPageContent<ShellDestination.Chat>,
    val admin: ShellAdminPages = ShellAdminPages(),
)

/**
 * letta-mobile-ckmub.3.4: the desktop's pages for the shared ShellPage switch. The shared pages
 * (Home, Memory, Schedules, Channels, Skills, Settings) are the sharedUI bindings over the desktop's
 * controllers; Overview is the shared Settings page on its About section (S10, ckmub.3.12); the chat
 * page is the desktop chat container and Providers the desktop binding of the shared
 * ProviderSettingsPane; Runs, Templates and Projects are [DesktopHostPages.admin] (letta-mobile-ckmub.2.6).
 */
internal fun desktopShellPageDeps(
    inputs: DestinationContentInputs,
    actions: DestinationContentActions,
    pages: DesktopHostPages,
): ShellPageDeps = ShellPageDeps(
    home = desktopHomePage(inputs, actions),
    chat = pages.chat,
    memory = desktopMemoryPage(inputs.memoryState, actions),
    schedules = shellSchedulesPage(inputs.schedule, actions.schedules),
    channels = shellChannelsPage(inputs.channels, actions.channels, ChannelsPageOptions(slots = DesktopChannelsPageSlots)),
    skills = desktopSkillsPage(inputs, actions),
    settings = desktopSettingsPage(inputs, actions),
    providers = inputs.state.modelControl?.let { session ->
        ShellPageContent { _, modifier -> ProvidersDestinationContent(session = session, modifier = modifier) }
    },
    admin = pages.admin,
)

/** Home waits behind the connection panel until the backend is there. */
private fun desktopHomePage(
    inputs: DestinationContentInputs,
    actions: DestinationContentActions,
): ShellPageContent<ShellDestination.Home> {
    val home = shellHomePage(inputs.home.state, actions.home, inputs.home.options)
    return ShellPageContent { destination, modifier ->
        if (inputs.chat.connectionState in ConnectionPendingStates) {
            val connection = inputs.chat.let { HomeConnectionState(it.chatScreenStatus, it.errorMessage, loading = it.isLoading) }
            HomeConnectionPanel(connection, actions.onRetryConnection, modifier, wordmark = "LETTA DESKTOP")
        } else {
            home.Content(destination, modifier)
        }
    }
}

private val ConnectionPendingStates = setOf(
    ChatConnectionState.Loading,
    ChatConnectionState.ConfigNeeded,
    ChatConnectionState.Offline,
)

/** The shared Memory page over the desktop's memory graph and MemFS controllers. */
private fun desktopMemoryPage(
    memoryState: MemoryPageState,
    actions: DestinationContentActions,
): ShellPageContent<ShellDestination.Memory> = ShellPageContent { destination, modifier ->
    val files by actions.memfs.state.collectAsState()
    var view by rememberMemoryWorkspaceView()
    val binding = MemoryWorkspaceBinding(
        memory = memoryState,
        memoryActions = actions.memory,
        files = files,
        filesActions = actions.memfs,
        onFilesAgentChange = actions.memfs::selectAgent,
        view = view,
        onViewChange = { view = it },
    )
    shellMemoryPage(binding).Content(destination, modifier)
}

/** letta-mobile-zxk2n.32: the shared Skills page (skills, then the agent's tools), as on Android. */
private fun desktopSkillsPage(
    inputs: DestinationContentInputs,
    actions: DestinationContentActions,
): ShellPageContent<ShellDestination.Skills> {
    val skills = inputs.skills
    return shellSkillsPage(
        state = SkillsPageState(
            skills = skills.catalog.skills,
            installedSkillNames = skills.catalog.installedSkillNames,
            skillsLoading = skills.catalog.isLoading,
            skillsError = skills.catalog.errorMessage,
            skillActionError = skills.catalog.actionError,
            canManageSkills = skills.catalog.canManage,
            focusedAgentName = skills.focusedAgentName,
            tools = inputs.toolLibraryState,
        ),
        actions = SkillsPageActions(
            onRefreshSkills = actions.skills.onRefresh,
            onInstallSkill = actions.skills.onInstall,
            onUninstallSkill = actions.skills.onUninstall,
            onDismissSkillActionError = actions.skills.onDismissActionError,
            onRefreshTools = actions.tools.onRefresh,
            onToolTagToggled = actions.tools.onTagToggled,
            onClearToolTags = actions.tools.onClearTags,
            onLoadMoreTools = actions.tools.onLoadMore,
        ),
        options = SkillsPageOptions(slots = DesktopChannelsPageSlots),
    )
}

/** The desktop's compact Jewel search field and icon-only refresh in the shared pages' headers. */
internal val DesktopChannelsPageSlots = ChannelsPageSlots(
    searchField = ChannelsSearchFieldSlot { query, onQueryChange, placeholder, modifier ->
        DesktopTextField(value = query, onValueChange = onQueryChange, placeholder = placeholder, modifier = modifier)
    },
    refreshAction = ChannelsRefreshActionSlot { onRefresh, modifier ->
        DesktopIconButton(imageVector = Icons.Outlined.Refresh, contentDescription = "Refresh", onClick = onRefresh, modifier = modifier)
    },
)
