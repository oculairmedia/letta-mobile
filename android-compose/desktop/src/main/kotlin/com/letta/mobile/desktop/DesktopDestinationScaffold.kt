package com.letta.mobile.desktop

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
import androidx.compose.material.icons.outlined.Hub
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
import com.letta.mobile.desktop.channels.DesktopChannelLibraryState
import com.letta.mobile.desktop.channels.DesktopChannelLibrarySurface
import com.letta.mobile.desktop.memory.DesktopBlockApi
import com.letta.mobile.desktop.memory.DesktopMemorySurface
import com.letta.mobile.desktop.memory.DesktopMemorySurfaceState
import com.letta.mobile.desktop.schedules.DesktopScheduleLibraryState
import com.letta.mobile.desktop.schedules.DesktopScheduleSurface
import com.letta.mobile.desktop.skills.DesktopSkillsSurface
import com.letta.mobile.desktop.skills.DesktopSkillsSurfaceActions
import com.letta.mobile.desktop.skills.DesktopSkillsSurfaceState
import com.letta.mobile.desktop.tools.DesktopToolLibraryState

internal data class DestinationMemoryActions(
    val onRefresh: () -> Unit,
    val onAgentSelected: (String) -> Unit,
)

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
    val memoryState: DesktopMemorySurfaceState,
    val schedule: DestinationScheduleInputs,
    val channelLibraryState: DesktopChannelLibraryState,
    val toolLibraryState: DesktopToolLibraryState,
    val blockApi: DesktopBlockApi?,
    val skills: DestinationSkillsInputs,
)

internal data class DestinationContentActions(
    val memory: DestinationMemoryActions,
    val schedules: DestinationScheduleActions,
    val onChannelsRefresh: () -> Unit,
    val tools: DestinationToolsActions,
    val skills: DestinationSkillsActions,
    val onConfigSaved: (LettaConfig) -> Unit,
    val onTokenCleared: () -> Unit,
)

private data class DestinationSettingsActions(
    val onConfigSaved: (LettaConfig) -> Unit,
    val onTokenCleared: () -> Unit,
)

private val DesktopDestination.icon: ImageVector
    get() = when (this) {
        DesktopDestination.Overview -> Icons.Outlined.Dashboard
        DesktopDestination.Agents -> Icons.Outlined.SmartToy
        DesktopDestination.Memory -> Icons.Outlined.Memory
        DesktopDestination.Schedules -> Icons.Outlined.Schedule
        DesktopDestination.Channels -> Icons.Outlined.Hub
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
        DesktopDestination.Memory -> MemoryDestinationContent(
            memoryState = inputs.memoryState,
            blockApi = inputs.blockApi,
            actions = actions.memory,
            modifier = modifier,
        )
        DesktopDestination.Schedules -> SchedulesDestinationContent(
            inputs = inputs.schedule,
            actions = actions.schedules,
            modifier = modifier,
        )
        DesktopDestination.Channels -> ChannelsDestinationContent(
            channelLibraryState = inputs.channelLibraryState,
            onChannelsRefresh = actions.onChannelsRefresh,
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
            destination = destination,
            state = inputs.state,
            settings = DestinationSettingsActions(
                onConfigSaved = actions.onConfigSaved,
                onTokenCleared = actions.onTokenCleared,
            ),
            modifier = modifier,
        )
    }
}

@Composable
private fun MemoryDestinationContent(
    memoryState: DesktopMemorySurfaceState,
    blockApi: DesktopBlockApi?,
    actions: DestinationMemoryActions,
    modifier: Modifier = Modifier,
) {
    DesktopMemorySurface(
        state = memoryState,
        onRefresh = actions.onRefresh,
        onAgentSelected = actions.onAgentSelected,
        modifier = modifier,
        blockApi = blockApi,
        onBlockChanged = actions.onRefresh,
    )
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
    channelLibraryState: DesktopChannelLibraryState,
    onChannelsRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DesktopChannelLibrarySurface(
        state = channelLibraryState,
        onRefresh = onChannelsRefresh,
        modifier = modifier,
    )
}

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
    destination: DesktopDestination,
    state: DesktopBootstrapState,
    settings: DestinationSettingsActions,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 32.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { DestinationHeader(destination) }
        scrollableDestinationItems(
            destination = destination,
            state = state,
            settings = settings,
        )
    }
}

@Composable
private fun DestinationHeader(destination: DesktopDestination) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
    destination: DesktopDestination,
    state: DesktopBootstrapState,
    settings: DestinationSettingsActions,
) {
    when (destination) {
        DesktopDestination.Overview -> {
            item { BackendCard(state.config) }
            item { StartupReadinessCard(state.featureReadiness) }
        }
        DesktopDestination.Settings -> {
            item {
                BackendSettingsCard(
                    config = state.config,
                    onConfigSaved = settings.onConfigSaved,
                    onTokenCleared = settings.onTokenCleared,
                )
            }
        }
        else -> Unit
    }
}
