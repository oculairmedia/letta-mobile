package com.letta.mobile.ui.screens.bot

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.bot.config.BotConfig
import com.letta.mobile.bot.config.BotScheduledJob
import com.letta.mobile.bot.core.ConversationMode
import com.letta.mobile.bot.skills.BotSkill
import com.letta.mobile.bot.skills.BotSkillActivationRule
import com.letta.mobile.ui.components.Accordions
import com.letta.mobile.ui.components.CardGroup
import com.letta.mobile.ui.components.LettaSearchBar
import com.letta.mobile.ui.icons.LettaIcons
import java.time.LocalDate

// ---------------------------------------------------------------------------
// General Section
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GeneralSection(vm: BotConfigEditViewModel) {
    var expanded by remember { mutableStateOf(true) }
    Accordions(
        title = "General",
        subtitle = "Display name, mode, connectivity",
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        CardGroup {
            item(
                headlineContent = {
                    OutlinedTextField(
                        value = vm.displayName,
                        onValueChange = { vm.displayName = it },
                        label = { Text("Display Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                },
                trailingContent = {
                    HelpIcon(
                        title = "Display Name",
                        description = "A friendly name shown in the config list. Optional.",
                    )
                },
            )
            item(
                headlineContent = { AgentSearchField(vm) },
                trailingContent = {
                    HelpIcon(
                        title = "Heartbeat agent",
                        description = "The Letta agent that scheduled heartbeats and cron jobs target. " +
                            "Interactive chats use the agent of the active chat — this only matters when " +
                            "the bot is firing in the background with no UI.",
                    )
                },
            )
            item(
                headlineContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Mode", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            HelpIcon(
                                title = "Connection Mode",
                                description = "Local: connects to the Letta server configured in app settings.\n\nRemote: connects to a separate bot server at a custom URL.",
                            )
                        }
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = vm.mode == BotConfig.Mode.LOCAL,
                                onClick = { vm.mode = BotConfig.Mode.LOCAL },
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                                label = { Text("Local") },
                            )
                            SegmentedButton(
                                selected = vm.mode == BotConfig.Mode.REMOTE,
                                onClick = { vm.mode = BotConfig.Mode.REMOTE },
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                                label = { Text("Remote") },
                            )
                        }
                    }
                },
            )
            if (vm.mode == BotConfig.Mode.REMOTE) {
                item(
                    headlineContent = {
                        OutlinedTextField(
                            value = vm.remoteUrl,
                            onValueChange = { vm.remoteUrl = it },
                            label = { Text("Remote URL") },
                            placeholder = { Text("http://192.168.1.100:3000") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    },
                    trailingContent = {
                        HelpIcon(
                            title = "Remote Server URL",
                            description = "The URL of your remote bot server (e.g. http://192.168.1.100:3000). Only used in Remote mode.",
                        )
                    },
                )
                item(
                    headlineContent = {
                        OutlinedTextField(
                            value = vm.remoteToken,
                            onValueChange = { vm.remoteToken = it },
                            label = { Text("Remote Token") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                        )
                    },
                    trailingContent = {
                        HelpIcon(
                            title = "Remote Auth Token",
                            description = "Authentication token for your remote bot server's API. Only used in Remote mode.",
                        )
                    },
                )
            }
            item(
                headlineContent = { Text("Enabled") },
                supportingContent = { Text("Include this config when bot service starts") },
                trailingContent = {
                    Switch(checked = vm.enabled, onCheckedChange = { vm.enabled = it })
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Agent search field
// ---------------------------------------------------------------------------

@Composable
internal fun AgentSearchField(vm: BotConfigEditViewModel) {
    val agents by vm.agents.collectAsStateWithLifecycle()

    Column {
        if (vm.heartbeatAgentId.isNotBlank() && !vm.agentSearchExpanded) {
            SelectedAgentCard(vm = vm)
        } else {
            Text("Heartbeat agent (optional)", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(4.dp))
            LettaSearchBar(
                query = vm.agentSearchQuery,
                onQueryChange = {
                    vm.agentSearchQuery = it
                    vm.agentSearchExpanded = true
                },
                onClear = {
                    vm.agentSearchQuery = ""
                    vm.agentSearchExpanded = false
                },
                placeholder = "Search agents…",
                compact = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        AnimatedVisibility(visible = vm.agentSearchExpanded && vm.agentSearchQuery.isNotBlank()) {
            AgentSearchResults(vm = vm, filtered = vm.filteredAgents())
        }

        AnimatedVisibility(visible = vm.agentSearchExpanded && vm.agentSearchQuery.isBlank() && agents.isNotEmpty()) {
            AgentSearchAllResults(vm = vm, agents = agents)
        }
    }
}

// ---------------------------------------------------------------------------
// Conversation Section
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationSection(vm: BotConfigEditViewModel) {
    var expanded by remember { mutableStateOf(false) }
    Accordions(
        title = "Conversation",
        subtitle = "Routing, channels",
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        CardGroup {
            item(
                headlineContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Conversation Mode", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            HelpIcon(
                                title = "Conversation Routing",
                                description = "Per Chat: each chat gets its own conversation.\n\nPer Channel: one conversation per channel.\n\nShared: all messages use a single conversation.\n\nDisabled: stateless — no conversation context.",
                            )
                        }
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            ConversationMode.entries.forEachIndexed { index, entry ->
                                SegmentedButton(
                                    selected = vm.conversationMode == entry,
                                    onClick = { vm.conversationMode = entry },
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = ConversationMode.entries.size,
                                    ),
                                    label = { Text(entry.name.replace("_", " "), style = MaterialTheme.typography.labelSmall) },
                                )
                            }
                        }
                    }
                },
            )
            if (vm.conversationMode == ConversationMode.SHARED) {
                item(
                    headlineContent = {
                        OutlinedTextField(
                            value = vm.sharedConversationId,
                            onValueChange = { vm.sharedConversationId = it },
                            label = { Text("Shared Conversation ID") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    },
                    trailingContent = {
                        HelpIcon(
                            title = "Shared Conversation",
                            description = "The fixed conversation ID to route all messages to when using Shared mode.",
                        )
                    },
                )
            }
            item(
                headlineContent = {
                    OutlinedTextField(
                        value = vm.channels,
                        onValueChange = { vm.channels = it },
                        label = { Text("Channels") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                },
                trailingContent = {
                    HelpIcon(
                        title = "Message Channels",
                        description = "Comma-separated list of channels this bot listens on. Default: in_app. Other options: notification.",
                    )
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Heartbeat Section
// ---------------------------------------------------------------------------

@Composable
internal fun HeartbeatSection(vm: BotConfigEditViewModel) {
    var expanded by remember { mutableStateOf(false) }
    Accordions(
        title = "Heartbeat",
        subtitle = if (vm.heartbeatEnabled) "Every ${vm.heartbeatIntervalMinutes} min" else "Disabled",
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        CardGroup {
            item(
                headlineContent = { Text("Heartbeat Enabled") },
                supportingContent = { Text("Periodically check in with agent") },
                trailingContent = {
                    Switch(checked = vm.heartbeatEnabled, onCheckedChange = { vm.heartbeatEnabled = it })
                },
            )
            if (vm.heartbeatEnabled) {
                item(
                    headlineContent = {
                        OutlinedTextField(
                            value = vm.heartbeatIntervalMinutes.toString(),
                            onValueChange = { vm.heartbeatIntervalMinutes = it.toLongOrNull() ?: 60L },
                            label = { Text("Interval (minutes)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    },
                    trailingContent = {
                        HelpIcon(
                            title = "Heartbeat Interval",
                            description = "How often (in minutes) the bot sends a heartbeat to the agent. Lower values use more battery.",
                        )
                    },
                )
                item(
                    headlineContent = {
                        OutlinedTextField(
                            value = vm.heartbeatMessage,
                            onValueChange = { vm.heartbeatMessage = it },
                            label = { Text("Heartbeat Message") },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 3,
                        )
                    },
                    trailingContent = {
                        HelpIcon(
                            title = "Heartbeat Prompt",
                            description = "The message sent to the agent on each heartbeat. The agent can choose to respond or stay silent.",
                        )
                    },
                )
                item(
                    headlineContent = { Text("Requires Charging") },
                    supportingContent = { Text("Only fire when plugged in") },
                    trailingContent = {
                        Switch(
                            checked = vm.heartbeatRequiresCharging,
                            onCheckedChange = { vm.heartbeatRequiresCharging = it },
                        )
                    },
                )
                item(
                    headlineContent = { Text("Requires Wi-Fi") },
                    supportingContent = { Text("Only fire on unmetered connections") },
                    trailingContent = {
                        Switch(
                            checked = vm.heartbeatRequiresUnmeteredNetwork,
                            onCheckedChange = { vm.heartbeatRequiresUnmeteredNetwork = it },
                        )
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// API Server Section
// ---------------------------------------------------------------------------

@Composable
internal fun ApiServerSection(vm: BotConfigEditViewModel) {
    var expanded by remember { mutableStateOf(false) }
    Accordions(
        title = "API Server",
        subtitle = if (vm.apiServerEnabled) "Port ${vm.apiServerPort}" else "Disabled",
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        CardGroup {
            item(
                headlineContent = { Text("API Server Enabled") },
                supportingContent = { Text("Expose a local REST API on this device") },
                trailingContent = {
                    Switch(checked = vm.apiServerEnabled, onCheckedChange = { vm.apiServerEnabled = it })
                },
            )
            if (vm.apiServerEnabled) {
                item(
                    headlineContent = {
                        OutlinedTextField(
                            value = vm.apiServerPort.toString(),
                            onValueChange = { vm.apiServerPort = it.toIntOrNull() ?: 8080 },
                            label = { Text("Port") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    },
                    trailingContent = {
                        HelpIcon(
                            title = "API Server Port",
                            description = "The TCP port for the embedded API server (default: 8080). Must be unique if running multiple bots.",
                        )
                    },
                )
                item(
                    headlineContent = {
                        OutlinedTextField(
                            value = vm.apiServerToken,
                            onValueChange = { vm.apiServerToken = it },
                            label = { Text("Auth Token") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                        )
                    },
                    trailingContent = {
                        HelpIcon(
                            title = "API Auth Token",
                            description = "Bearer token required to authenticate requests to the embedded API server. Leave blank to generate a token on save.",
                        )
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Scheduled Jobs Section
// ---------------------------------------------------------------------------

@Composable
internal fun ScheduledJobsSection(vm: BotConfigEditViewModel) {
    var expanded by remember { mutableStateOf(false) }
    Accordions(
        title = "Scheduled Jobs",
        subtitle = "${vm.scheduledJobs.size} job${if (vm.scheduledJobs.size != 1) "s" else ""}",
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        CardGroup {
            vm.scheduledJobs.forEach { job ->
                item(
                    headlineContent = {
                        ScheduledJobEditor(
                            job = job,
                            onUpdate = { vm.updateScheduledJob(it) },
                            onRemove = { vm.removeScheduledJob(job.id) },
                        )
                    },
                )
            }
            item(
                onClick = { vm.addScheduledJob() },
                headlineContent = { Text("Add Scheduled Job") },
                leadingContent = { Icon(LettaIcons.Add, contentDescription = null) },
            )
        }
    }
}

@Composable
internal fun ScheduledJobEditor(
    job: BotScheduledJob,
    onUpdate: (BotScheduledJob) -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Job", style = MaterialTheme.typography.labelMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = job.enabled,
                        onCheckedChange = { onUpdate(job.copy(enabled = it)) },
                    )
                    IconButton(onClick = onRemove) {
                        Icon(LettaIcons.Delete, contentDescription = "Remove job", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
            OutlinedTextField(
                value = job.displayName,
                onValueChange = { onUpdate(job.copy(displayName = it)) },
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = job.message,
                onValueChange = { onUpdate(job.copy(message = it)) },
                label = { Text("Message") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = job.cronExpression,
                    onValueChange = { onUpdate(job.copy(cronExpression = it)) },
                    label = { Text("Cron Expression") },
                    placeholder = { Text("0 */30 * * *") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                HelpIcon(
                    title = "Cron Schedule",
                    description = "A cron expression defining when this job runs.\n\nFormat: minute hour day month weekday\n\nExamples:\n0 */30 * * * = every 30 minutes\n0 9 * * 1-5 = 9 AM weekdays\n0 0 * * * = every midnight",
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Requires Charging", style = MaterialTheme.typography.bodySmall)
                Switch(
                    checked = job.requiresCharging,
                    onCheckedChange = { onUpdate(job.copy(requiresCharging = it)) },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Requires Wi-Fi", style = MaterialTheme.typography.bodySmall)
                Switch(
                    checked = job.requiresUnmeteredNetwork,
                    onCheckedChange = { onUpdate(job.copy(requiresUnmeteredNetwork = it)) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Advanced Section
// ---------------------------------------------------------------------------

@Composable
internal fun AdvancedSection(vm: BotConfigEditViewModel) {
    var expanded by remember { mutableStateOf(false) }
    var showSkillPicker by remember { mutableStateOf(false) }
    Accordions(
        title = "Advanced",
        subtitle = "Auto start, directives, envelope, context",
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        CardGroup {
            item(
                headlineContent = { Text("Auto Start") },
                supportingContent = { Text("Start bot when app launches") },
                trailingContent = {
                    Switch(checked = vm.autoStart, onCheckedChange = { vm.autoStart = it })
                },
            )
            item(
                headlineContent = { Text("Directives Enabled") },
                supportingContent = { Text("Parse structured directives in responses") },
                trailingContent = {
                    Switch(checked = vm.directivesEnabled, onCheckedChange = { vm.directivesEnabled = it })
                },
            )
            item(
                headlineContent = {
                    OutlinedTextField(
                        value = vm.envelopeTemplate,
                        onValueChange = { vm.envelopeTemplate = it },
                        label = { Text("Envelope Template") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 5,
                    )
                },
                trailingContent = {
                    HelpIcon(
                        title = "Message Envelope",
                        description = "A template that wraps each user message with contextual metadata before sending to the agent.\n\nPlaceholders:\n{{channel}} — channel ID\n{{sender}} — sender ID\n{{sender_name}} — sender display name\n{{text}} — the message text\n{{timestamp}} — current timestamp\n{{context}} — device context block\n{{memory}} — memory block\n\nLeave blank for the default envelope format.",
                    )
                },
            )
            item(
                headlineContent = { SkillsEditor(vm = vm, onOpenPicker = { showSkillPicker = true }) },
                supportingContent = {
                    val unknownSkillIds = vm.unknownEnabledSkillIds()
                    Text(
                        text = when {
                            unknownSkillIds.isNotEmpty() -> "Unknown skill IDs: ${unknownSkillIds.joinToString(", ")}"
                            vm.availableSkillIds.isBlank() -> "No bundled skills are available."
                            else -> {
                            "Bundled skills: ${vm.availableSkillIds}"
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (unknownSkillIds.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingContent = {
                    HelpIcon(
                        title = "Enabled Skills",
                        description = "Optional bundled skills to load for this bot session. Skills add prompt guidance and limit synced local tools to the capabilities required by the enabled bundles.",
                    )
                },
            )
            item(
                headlineContent = {
                    OutlinedTextField(
                        value = vm.contextProviders,
                        onValueChange = { vm.contextProviders = it },
                        label = { Text("Context Providers") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                },
                trailingContent = {
                    HelpIcon(
                        title = "Device Context",
                        description = "Comma-separated list of device context providers to include in the message envelope.\n\nAvailable providers:\n• battery — battery level and charging status\n• connectivity — network type (WiFi, cellular)\n• time — current date, time, and timezone",
                    )
                },
            )
        }

        if (showSkillPicker) {
            SkillPickerDialog(
                availableSkills = vm.availableSkills,
                selectedSkillIds = vm.enabledSkills,
                onDismiss = { showSkillPicker = false },
                onConfirm = {
                    vm.updateEnabledSkills(it)
                    showSkillPicker = false
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SkillsEditor(
    vm: BotConfigEditViewModel,
    onOpenPicker: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Enabled Skills", style = MaterialTheme.typography.bodyMedium)
        if (vm.enabledSkills.isEmpty()) {
            Text(
                text = "No skills selected. Choose a bundled skill to add prompt guidance and scoped tool access.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                vm.enabledSkills.forEach { skillId ->
                    val skill = vm.getSkill(skillId)
                    InputChip(
                        selected = false,
                        onClick = { vm.removeEnabledSkill(skillId) },
                        label = { Text(skill?.displayName ?: skillId) },
                        trailingIcon = {
                            Icon(
                                LettaIcons.Close,
                                contentDescription = stringResource(com.letta.mobile.R.string.action_remove),
                                modifier = Modifier.size(16.dp),
                            )
                        },
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                vm.enabledSkills.forEach { skillId ->
                    val skill = vm.getSkill(skillId)
                    if (skill != null) {
                        SelectedSkillSummary(skill = skill)
                    } else {
                        Text(
                            text = "$skillId — Unknown bundled skill",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        TextButton(onClick = onOpenPicker) {
            Text(if (vm.availableSkills.isEmpty()) "No bundled skills available" else "Choose Skills")
        }
    }
}

@Composable
internal fun SelectedSkillSummary(skill: BotSkill) {
    val isActiveToday = skill.activationRule.isActive(LocalDate.now())
    val activationLabel = when (skill.activationRule) {
        BotSkillActivationRule.Always -> "Always active"
        is BotSkillActivationRule.WeekdayOnly -> if (isActiveToday) "Active today" else "Inactive today"
    }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "${skill.displayName} • $activationLabel",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        skill.description.takeIf { it.isNotBlank() }?.let { description ->
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
