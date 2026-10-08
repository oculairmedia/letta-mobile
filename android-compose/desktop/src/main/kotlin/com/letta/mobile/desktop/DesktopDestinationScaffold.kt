package com.letta.mobile.desktop

import com.letta.mobile.data.chat.runtime.ChatConnectionState
import com.letta.mobile.desktop.chat.ChatStatePanel
import com.letta.mobile.desktop.chat.DesktopChatSurfaceState

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.schedules.CronTask
import com.letta.mobile.data.skills.Skill
import com.letta.mobile.desktop.home.DesktopHomeInputs
import com.letta.mobile.ui.shell.pages.home.HomePage
import com.letta.mobile.ui.shell.pages.home.HomePageCallbacks
import com.letta.mobile.data.channel.ChannelsPageActions
import com.letta.mobile.data.channel.ChannelsPageState
import com.letta.mobile.ui.shell.pages.channels.ChannelsPage
import com.letta.mobile.ui.shell.pages.channels.ChannelsPageOptions
import com.letta.mobile.ui.shell.pages.channels.ChannelsPageSlots
import com.letta.mobile.ui.shell.pages.channels.ChannelsRefreshActionSlot
import com.letta.mobile.ui.shell.pages.channels.ChannelsSearchFieldSlot
import androidx.compose.material.icons.outlined.Refresh
import com.letta.mobile.data.memory.graph.MemoryPageActions
import com.letta.mobile.data.memory.graph.MemoryPageState
import com.letta.mobile.data.memory.memfs.MemfsPageController
import com.letta.mobile.desktop.workspace.DesktopMemoryDestination
import com.letta.mobile.desktop.schedules.DesktopScheduleLibraryState
import com.letta.mobile.desktop.schedules.DesktopScheduleSurface
import com.letta.mobile.desktop.skills.DesktopSkillsSurface
import com.letta.mobile.desktop.skills.DesktopSkillsSurfaceActions
import com.letta.mobile.desktop.skills.DesktopSkillsSurfaceState
import com.letta.mobile.desktop.tools.DesktopToolLibraryState
import com.letta.mobile.ui.theme.LettaDimens

internal data class DestinationScheduleInputs(
    val scheduleLibraryState: DesktopScheduleLibraryState,
    val crons: List<CronTask>,
    val focusedAgentId: String?,
    val canCreateCron: Boolean,
)

internal data class DestinationScheduleActions(
    val onRefresh: () -> Unit,
    val onAgentSelected: (String) -> Unit,
    val onDeleteCron: (String) -> Unit,
    val onCreateCron: (
        agentId: String?,
        name: String,
        prompt: String,
        cron: String,
        recurring: Boolean,
        timezone: String,
    ) -> Unit,
)

internal data class DestinationSkillsInputs(
    val skills: List<Skill>,
    val installedSkillNames: Set<String>,
    val skillsLoading: Boolean,
    val skillsError: String?,
    val canManageSkills: Boolean,
    val focusedAgentName: String?,
)

internal data class DestinationSkillsActions(
    val onRefresh: () -> Unit,
    val onInstall: (String) -> Unit,
    val onUninstall: (String) -> Unit,
)

internal data class DestinationToolsActions(
    val onRefresh: () -> Unit,
    val onSearchQueryChanged: (String) -> Unit,
    val onTagToggled: (String) -> Unit,
    val onClearTags: () -> Unit,
    val onLoadMore: () -> Unit,
)

private data class DestinationAgentsInputs(
    val skills: DestinationSkillsInputs,
    val toolLibraryState: DesktopToolLibraryState,
)

private data class DestinationAgentsActions(
    val skills: DestinationSkillsActions,
    val tools: DestinationToolsActions,
)

internal data class DestinationContentInputs(
    val state: DesktopBootstrapState,
    /** Agent-rail recency window in days; 0 shows every agent. */
    val railRecencyDays: Int = RAIL_RECENCY_DAYS_DEFAULT,
    val home: DesktopHomeInputs,
    val chat: DesktopChatSurfaceState,
    val memoryState: MemoryPageState,
    val schedule: DestinationScheduleInputs,
    val channels: ChannelsPageState,
    val toolLibraryState: DesktopToolLibraryState,
    val skills: DestinationSkillsInputs,
    val nucleus: DesktopNucleusState,
    val localRuntimeProvider: DesktopLocalRuntimeProviderState,
    val localBackendDirectory: DesktopLocalBackendDirectoryState,
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
    val schedules: DestinationScheduleActions,
    val channels: ChannelsPageActions,
    val tools: DestinationToolsActions,
    val skills: DestinationSkillsActions,
    val onConfigSaved: (LettaConfig) -> Unit,
    val onTokenCleared: () -> Unit,
    val onIrohIdentityReset: () -> Unit,
    val onRailRecencyDaysChange: (Int) -> Unit = {},
    val nucleus: DestinationNucleusActions,
    val localRuntimeProvider: DesktopLocalRuntimeProviderActions,
    val localBackendDirectory: DesktopLocalBackendDirectoryActions,
)

private data class DestinationSettingsActions(
    val onConfigSaved: (LettaConfig) -> Unit,
    val onTokenCleared: () -> Unit,
    val onIrohIdentityReset: () -> Unit,
    val onRailRecencyDaysChange: (Int) -> Unit = {},
    val nucleus: DestinationNucleusActions,
    val localRuntimeProvider: DesktopLocalRuntimeProviderActions,
    val localBackendDirectory: DesktopLocalBackendDirectoryActions,
)

private data class ScrollableDestinationInputs(
    val destination: DesktopDestination,
    val state: DesktopBootstrapState,
    val nucleus: DesktopNucleusState,
    val localRuntimeProvider: DesktopLocalRuntimeProviderState,
    val localBackendDirectory: DesktopLocalBackendDirectoryState,
    val railRecencyDays: Int,
)

private val DesktopDestination.icon: ImageVector
    get() = when (this) {
        DesktopDestination.Home -> Icons.Outlined.Home
        DesktopDestination.Overview -> Icons.Outlined.Dashboard
        DesktopDestination.Agents -> Icons.Outlined.SmartToy
        DesktopDestination.Memory -> Icons.Outlined.Memory
        DesktopDestination.Schedules -> Icons.Outlined.Schedule
        DesktopDestination.Channels -> Icons.Outlined.Hub
        DesktopDestination.Providers -> Icons.Outlined.Tune
        DesktopDestination.Conversations -> Icons.Outlined.Forum
        DesktopDestination.Settings -> Icons.Outlined.Settings
    }

@Composable
internal fun DestinationContent(
    destination: DesktopDestination,
    inputs: DestinationContentInputs,
    actions: DestinationContentActions,
    modifier: Modifier = Modifier,
) {
    when (destination) {
        // The shared Home page; see HomePage's KDoc for the Letta Code mod / A2UI document seam.
        DesktopDestination.Home -> HomeDestinationContent(
            inputs = inputs,
            actions = actions,
            modifier = modifier,
        )
        DesktopDestination.Memory -> DesktopMemoryDestination(
            memoryState = inputs.memoryState,
            actions = actions.memory,
            memfs = actions.memfs,
            modifier = modifier,
        )
        DesktopDestination.Schedules -> SchedulesDestinationContent(
            inputs = inputs.schedule,
            actions = actions.schedules,
            modifier = modifier,
        )
        DesktopDestination.Providers -> inputs.state.modelControl?.let { session ->
            ProvidersDestinationContent(session = session, modifier = modifier)
        }
        DesktopDestination.Channels -> ChannelsDestinationContent(
            state = inputs.channels,
            actions = actions.channels,
            modifier = modifier,
        )
        DesktopDestination.Agents -> AgentsDestinationContent(
            inputs = DestinationAgentsInputs(
                skills = inputs.skills,
                toolLibraryState = inputs.toolLibraryState,
            ),
            actions = DestinationAgentsActions(
                skills = actions.skills,
                tools = actions.tools,
            ),
            modifier = modifier,
        )
        else -> ScrollableDestinationContent(
            inputs = ScrollableDestinationInputs(
                destination = destination,
                state = inputs.state,
                railRecencyDays = inputs.railRecencyDays,
                nucleus = inputs.nucleus,
                localRuntimeProvider = inputs.localRuntimeProvider,
                localBackendDirectory = inputs.localBackendDirectory,
            ),
            settings = DestinationSettingsActions(
                onConfigSaved = actions.onConfigSaved,
                onTokenCleared = actions.onTokenCleared,
                onIrohIdentityReset = actions.onIrohIdentityReset,
                onRailRecencyDaysChange = actions.onRailRecencyDaysChange,
                nucleus = actions.nucleus,
                localRuntimeProvider = actions.localRuntimeProvider,
                localBackendDirectory = actions.localBackendDirectory,
            ),
            modifier = modifier,
        )
    }
}

@Composable
private fun HomeDestinationContent(
    inputs: DestinationContentInputs,
    actions: DestinationContentActions,
    modifier: Modifier = Modifier,
) {
    if (inputs.chat.connectionState in setOf(
            ChatConnectionState.Loading,
            ChatConnectionState.ConfigNeeded,
            ChatConnectionState.Offline,
        )
    ) {
        ChatStatePanel(
            state = inputs.chat,
            onRetryConnection = actions.onRetryConnection,
            modifier = modifier,
        )
    } else {
        HomePage(
            state = inputs.home.state,
            callbacks = actions.home,
            modifier = modifier,
            options = inputs.home.options,
        )
    }
}

@Composable
private fun SchedulesDestinationContent(
    inputs: DestinationScheduleInputs,
    actions: DestinationScheduleActions,
    modifier: Modifier = Modifier,
) {
    DesktopScheduleSurface(
        state = inputs.scheduleLibraryState,
        onRefresh = actions.onRefresh,
        onAgentSelected = actions.onAgentSelected,
        modifier = modifier,
        crons = inputs.crons,
        focusedAgentId = inputs.focusedAgentId,
        onDeleteCron = actions.onDeleteCron,
        canCreate = inputs.canCreateCron,
        onCreateCron = actions.onCreateCron,
    )
}

@Composable
private fun ChannelsDestinationContent(
    state: ChannelsPageState,
    actions: ChannelsPageActions,
    modifier: Modifier = Modifier,
) {
    ChannelsPage(
        state = state,
        actions = actions,
        modifier = modifier,
        options = ChannelsPageOptions(slots = DesktopChannelsPageSlots),
    )
}

/** The desktop's compact Jewel search field and icon-only refresh in the shared Channels header. */
private val DesktopChannelsPageSlots = ChannelsPageSlots(
    searchField = ChannelsSearchFieldSlot { query, onQueryChange, placeholder, modifier ->
        DesktopTextField(value = query, onValueChange = onQueryChange, placeholder = placeholder, modifier = modifier)
    },
    refreshAction = ChannelsRefreshActionSlot { onRefresh, modifier ->
        DesktopIconButton(imageVector = Icons.Outlined.Refresh, contentDescription = "Refresh", onClick = onRefresh, modifier = modifier)
    },
)

@Composable
private fun AgentsDestinationContent(
    inputs: DestinationAgentsInputs,
    actions: DestinationAgentsActions,
    modifier: Modifier = Modifier,
) {
    val skills = inputs.skills
    DesktopSkillsSurface(
        state = DesktopSkillsSurfaceState(
            skills = skills.skills,
            installedSkillNames = skills.installedSkillNames,
            skillsLoading = skills.skillsLoading,
            skillsError = skills.skillsError,
            canManageSkills = skills.canManageSkills,
            focusedAgentName = skills.focusedAgentName,
            toolState = inputs.toolLibraryState,
        ),
        actions = DesktopSkillsSurfaceActions(
            onRefreshSkills = actions.skills.onRefresh,
            onInstallSkill = actions.skills.onInstall,
            onUninstallSkill = actions.skills.onUninstall,
            onToolsRefresh = actions.tools.onRefresh,
            onToolsSearchQueryChanged = actions.tools.onSearchQueryChanged,
            onToolsTagToggled = actions.tools.onTagToggled,
            onToolsClearTags = actions.tools.onClearTags,
            onToolsLoadMore = actions.tools.onLoadMore,
        ),
        modifier = modifier,
    )
}

@Composable
private fun ScrollableDestinationContent(
    inputs: ScrollableDestinationInputs,
    settings: DestinationSettingsActions,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = LettaDimens.Space.xxl, vertical = LettaDimens.Space.xl),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.lg),
    ) {
        item { DestinationHeader(inputs.destination) }
        scrollableDestinationItems(
            inputs = inputs,
            settings = settings,
        )
    }
}

@Composable
private fun DestinationHeader(destination: DesktopDestination) {
    Column(verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
        Text(
            text = destination.label,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = destination.summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun LazyListScope.scrollableDestinationItems(
    inputs: ScrollableDestinationInputs,
    settings: DestinationSettingsActions,
) {
    when (inputs.destination) {
        DesktopDestination.Overview -> {
            item { BackendCard(inputs.state.config) }
            item { StartupReadinessCard(inputs.state.featureReadiness) }
        }
        DesktopDestination.Settings -> {
            item {
                BackendSettingsCard(
                    config = inputs.state.config,
                    onConfigSaved = settings.onConfigSaved,
                    onTokenCleared = settings.onTokenCleared,
                    onIrohIdentityReset = settings.onIrohIdentityReset,
                )
            }
            item {
                LocalRuntimeProviderSettingsCard(
                    state = inputs.localRuntimeProvider,
                    actions = settings.localRuntimeProvider,
                )
            }
            item {
                DesktopLocalBackendDirectorySettingsCard(
                    state = inputs.localBackendDirectory,
                    actions = settings.localBackendDirectory,
                )
            }
            item { DesktopSharedChatPageSettingsCard() }
            item {
                DesktopRailSettingsCard(
                    recencyDays = inputs.railRecencyDays,
                    onRecencyDaysChange = settings.onRailRecencyDaysChange,
                )
            }
            item {
                DesktopNucleusSettingsCard(
                    state = inputs.nucleus,
                    actions = settings.nucleus,
                )
            }
        }
        else -> Unit
    }
}
