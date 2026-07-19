package com.letta.mobile.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.schedules.CronTask
import com.letta.mobile.data.skills.Skill
import com.letta.mobile.desktop.channels.DesktopChannelLibraryState
import com.letta.mobile.desktop.channels.DesktopChannelLibrarySurface
import com.letta.mobile.desktop.chat.DesktopChatSurfaceState
import com.letta.mobile.desktop.components.DesktopChipTab
import com.letta.mobile.desktop.data.desktopConfigIdFor
import com.letta.mobile.desktop.memory.DesktopBlockApi
import com.letta.mobile.desktop.memory.DesktopMemorySurface
import com.letta.mobile.desktop.memory.DesktopMemorySurfaceState
import com.letta.mobile.desktop.schedules.DesktopScheduleLibraryState
import com.letta.mobile.desktop.schedules.DesktopScheduleSurface
import com.letta.mobile.desktop.skills.DesktopSkillsSurface
import com.letta.mobile.desktop.tools.DesktopToolLibraryState
import org.jetbrains.jewel.ui.component.Text as JewelText
import org.jetbrains.jewel.ui.component.TextField as JewelTextField

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
    state: DesktopBootstrapState,
    chatState: DesktopChatSurfaceState,
    memoryState: DesktopMemorySurfaceState,
    scheduleLibraryState: DesktopScheduleLibraryState,
    channelLibraryState: DesktopChannelLibraryState,
    toolLibraryState: DesktopToolLibraryState,
    onChatConversationSelected: (String) -> Unit,
    onChatConversationDeleted: (String) -> Unit,
    onChatComposerTextChanged: (String) -> Unit,
    onChatSend: () -> Unit,
    onChatAttachImage: () -> Unit,
    onChatRemoveImageAttachment: (Int) -> Unit,
    onChatRetryConnection: () -> Unit,
    onMemoryRefresh: () -> Unit,
    onMemoryAgentSelected: (String) -> Unit,
    onSchedulesRefresh: () -> Unit,
    onScheduleAgentSelected: (String) -> Unit,
    onChannelsRefresh: () -> Unit,
    onToolsRefresh: () -> Unit,
    onToolsSearchQueryChanged: (String) -> Unit,
    onToolsTagToggled: (String) -> Unit,
    onToolsClearTags: () -> Unit,
    onToolsLoadMore: () -> Unit,
    onConfigSaved: (LettaConfig) -> Unit,
    onTokenCleared: () -> Unit,
    blockApi: DesktopBlockApi?,
    crons: List<CronTask>,
    onDeleteCron: (String) -> Unit,
    canCreateCron: Boolean,
    onCreateCron: (agentId: String?, name: String, prompt: String, cron: String, recurring: Boolean, timezone: String) -> Unit,
    focusedAgentId: String?,
    skills: List<Skill>,
    installedSkillNames: Set<String>,
    skillsLoading: Boolean,
    skillsError: String?,
    canManageSkills: Boolean,
    focusedAgentName: String?,
    onRefreshSkills: () -> Unit,
    onInstallSkill: (String) -> Unit,
    onUninstallSkill: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (destination) {
        DesktopDestination.Memory -> MemoryDestinationContent(
            memoryState = memoryState,
            onMemoryRefresh = onMemoryRefresh,
            onMemoryAgentSelected = onMemoryAgentSelected,
            blockApi = blockApi,
            modifier = modifier,
        )
        DesktopDestination.Schedules -> SchedulesDestinationContent(
            scheduleLibraryState = scheduleLibraryState,
            onSchedulesRefresh = onSchedulesRefresh,
            onScheduleAgentSelected = onScheduleAgentSelected,
            crons = crons,
            focusedAgentId = focusedAgentId,
            onDeleteCron = onDeleteCron,
            canCreateCron = canCreateCron,
            onCreateCron = onCreateCron,
            modifier = modifier,
        )
        DesktopDestination.Channels -> ChannelsDestinationContent(
            channelLibraryState = channelLibraryState,
            onChannelsRefresh = onChannelsRefresh,
            modifier = modifier,
        )
        DesktopDestination.Agents -> AgentsDestinationContent(
            skills = skills,
            installedSkillNames = installedSkillNames,
            skillsLoading = skillsLoading,
            skillsError = skillsError,
            canManageSkills = canManageSkills,
            focusedAgentName = focusedAgentName,
            onRefreshSkills = onRefreshSkills,
            onInstallSkill = onInstallSkill,
            onUninstallSkill = onUninstallSkill,
            toolLibraryState = toolLibraryState,
            onToolsRefresh = onToolsRefresh,
            onToolsSearchQueryChanged = onToolsSearchQueryChanged,
            onToolsTagToggled = onToolsTagToggled,
            onToolsClearTags = onToolsClearTags,
            onToolsLoadMore = onToolsLoadMore,
            modifier = modifier,
        )
        else -> ScrollableDestinationContent(
            destination = destination,
            state = state,
            onConfigSaved = onConfigSaved,
            onTokenCleared = onTokenCleared,
            modifier = modifier,
        )
    }
}

@Composable
private fun MemoryDestinationContent(
    memoryState: DesktopMemorySurfaceState,
    onMemoryRefresh: () -> Unit,
    onMemoryAgentSelected: (String) -> Unit,
    blockApi: DesktopBlockApi?,
    modifier: Modifier = Modifier,
) {
    DesktopMemorySurface(
        state = memoryState,
        onRefresh = onMemoryRefresh,
        onAgentSelected = onMemoryAgentSelected,
        modifier = modifier,
        blockApi = blockApi,
        onBlockChanged = onMemoryRefresh,
    )
}

@Composable
private fun SchedulesDestinationContent(
    scheduleLibraryState: DesktopScheduleLibraryState,
    onSchedulesRefresh: () -> Unit,
    onScheduleAgentSelected: (String) -> Unit,
    crons: List<CronTask>,
    focusedAgentId: String?,
    onDeleteCron: (String) -> Unit,
    canCreateCron: Boolean,
    onCreateCron: (agentId: String?, name: String, prompt: String, cron: String, recurring: Boolean, timezone: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    DesktopScheduleSurface(
        state = scheduleLibraryState,
        onRefresh = onSchedulesRefresh,
        onAgentSelected = onScheduleAgentSelected,
        modifier = modifier,
        crons = crons,
        focusedAgentId = focusedAgentId,
        onDeleteCron = onDeleteCron,
        canCreate = canCreateCron,
        onCreateCron = onCreateCron,
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
    skills: List<Skill>,
    installedSkillNames: Set<String>,
    skillsLoading: Boolean,
    skillsError: String?,
    canManageSkills: Boolean,
    focusedAgentName: String?,
    onRefreshSkills: () -> Unit,
    onInstallSkill: (String) -> Unit,
    onUninstallSkill: (String) -> Unit,
    toolLibraryState: DesktopToolLibraryState,
    onToolsRefresh: () -> Unit,
    onToolsSearchQueryChanged: (String) -> Unit,
    onToolsTagToggled: (String) -> Unit,
    onToolsClearTags: () -> Unit,
    onToolsLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DesktopSkillsSurface(
        skills = skills,
        installedSkillNames = installedSkillNames,
        skillsLoading = skillsLoading,
        skillsError = skillsError,
        canManageSkills = canManageSkills,
        focusedAgentName = focusedAgentName,
        onRefreshSkills = onRefreshSkills,
        onInstallSkill = onInstallSkill,
        onUninstallSkill = onUninstallSkill,
        toolState = toolLibraryState,
        onToolsRefresh = onToolsRefresh,
        onToolsSearchQueryChanged = onToolsSearchQueryChanged,
        onToolsTagToggled = onToolsTagToggled,
        onToolsClearTags = onToolsClearTags,
        onToolsLoadMore = onToolsLoadMore,
        modifier = modifier,
    )
}

@Composable
private fun ScrollableDestinationContent(
    destination: DesktopDestination,
    state: DesktopBootstrapState,
    onConfigSaved: (LettaConfig) -> Unit,
    onTokenCleared: () -> Unit,
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
            onConfigSaved = onConfigSaved,
            onTokenCleared = onTokenCleared,
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
    onConfigSaved: (LettaConfig) -> Unit,
    onTokenCleared: () -> Unit,
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
                    onConfigSaved = onConfigSaved,
                    onTokenCleared = onTokenCleared,
                )
            }
        }
        else -> Unit
    }
}
