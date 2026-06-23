package com.letta.mobile.desktop.skills

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.skills.Skill
import com.letta.mobile.desktop.DesktopButtonContent
import com.letta.mobile.desktop.DesktopControlText
import com.letta.mobile.desktop.DesktopDefaultButton
import com.letta.mobile.desktop.DesktopOutlinedButton
import com.letta.mobile.desktop.DesktopRadioChip
import com.letta.mobile.desktop.tools.DesktopToolLibrarySurface
import com.letta.mobile.desktop.tools.DesktopToolLibraryState

private enum class SkillsTab { Skills, Tools }

@Composable
fun DesktopSkillsSurface(
    skills: List<Skill>,
    installedSkillNames: Set<String>,
    skillsLoading: Boolean,
    skillsError: String?,
    canManageSkills: Boolean,
    focusedAgentName: String?,
    onRefreshSkills: () -> Unit,
    onInstallSkill: (String) -> Unit,
    onUninstallSkill: (String) -> Unit,
    toolState: DesktopToolLibraryState,
    onToolsRefresh: () -> Unit,
    onToolsSearchQueryChanged: (String) -> Unit,
    onToolsTagToggled: (String) -> Unit,
    onToolsClearTags: () -> Unit,
    onToolsLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableStateOf(SkillsTab.Skills) }
    Column(modifier = modifier.fillMaxHeight().background(MaterialTheme.colorScheme.surface)) {
        TabRow(tab = tab, onSelect = { tab = it })
        when (tab) {
            SkillsTab.Skills -> SkillsTabContent(
                skills = skills,
                installedSkillNames = installedSkillNames,
                skillsLoading = skillsLoading,
                skillsError = skillsError,
                canManageSkills = canManageSkills,
                focusedAgentName = focusedAgentName,
                onRefreshSkills = onRefreshSkills,
                onInstallSkill = onInstallSkill,
                onUninstallSkill = onUninstallSkill,
                modifier = Modifier.fillMaxSize(),
            )
            SkillsTab.Tools -> DesktopToolLibrarySurface(
                state = toolState,
                onRefresh = onToolsRefresh,
                onSearchQueryChanged = onToolsSearchQueryChanged,
                onTagToggled = onToolsTagToggled,
                onClearTags = onToolsClearTags,
                onLoadMore = onToolsLoadMore,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun TabRow(tab: SkillsTab, onSelect: (SkillsTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 32.dp, top = 24.dp, end = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DesktopRadioChip(selected = tab == SkillsTab.Skills, onClick = { onSelect(SkillsTab.Skills) }) {
            DesktopControlText("Skills")
        }
        DesktopRadioChip(selected = tab == SkillsTab.Tools, onClick = { onSelect(SkillsTab.Tools) }) {
            DesktopControlText("Tools")
        }
    }
}

@Composable
private fun SkillsTabContent(
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
    var showInstalledOnly by remember { mutableStateOf(true) }
    var selectedSkill by remember { mutableStateOf<Skill?>(null) }
    val installed = remember(skills, installedSkillNames) { skills.filter { it.name in installedSkillNames } }
    // The agent's installed list may include skills not present in the registry feed.
    val installedExtras = remember(installedSkillNames, skills) {
        val known = skills.map { it.name }.toSet()
        installedSkillNames.filter { it !in known }.map { Skill(name = it) }
    }
    val visible = if (showInstalledOnly) installed + installedExtras else skills

    Row(modifier = modifier) {
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    SkillsHeader(
                        installedCount = installedSkillNames.size,
                        totalCount = skills.size,
                        loading = skillsLoading,
                        onRefresh = onRefreshSkills,
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DesktopRadioChip(selected = showInstalledOnly, onClick = { showInstalledOnly = true }) {
                            DesktopControlText("Installed (${installedSkillNames.size})")
                        }
                        DesktopRadioChip(selected = !showInstalledOnly, onClick = { showInstalledOnly = false }) {
                            DesktopControlText("All (${skills.size})")
                        }
                    }
                }
                skillsError?.let { item { InfoCard(it, isError = true) } }
                if (skillsLoading && skills.isEmpty()) {
                    item { InfoCard("Loading skills from the active backend.") }
                } else if (visible.isEmpty()) {
                    item {
                        InfoCard(
                            if (showInstalledOnly) {
                                "No skills installed on ${focusedAgentName ?: "this agent"}. Switch to All to browse the registry."
                            } else {
                                "No skills available."
                            },
                        )
                    }
                } else {
                    items(items = visible, key = { it.name }) { skill ->
                        SkillRow(
                            skill = skill,
                            installed = skill.name in installedSkillNames,
                            canManage = canManageSkills,
                            selected = selectedSkill?.name == skill.name,
                            onClick = { selectedSkill = skill },
                            onInstall = { onInstallSkill(skill.name) },
                            onUninstall = { onUninstallSkill(skill.name) },
                        )
                    }
                }
            }
        }
        selectedSkill?.let { skill ->
            SkillDetailPanel(
                skill = skill,
                installed = skill.name in installedSkillNames,
                canManage = canManageSkills,
                onInstall = { onInstallSkill(skill.name) },
                onUninstall = { onUninstallSkill(skill.name) },
                onClose = { selectedSkill = null },
            )
        }
    }
}

@Composable
private fun SkillsHeader(
    installedCount: Int,
    totalCount: Int,
    loading: Boolean,
    onRefresh: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
            Text("Skills", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = "$installedCount installed · $totalCount in registry",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DesktopOutlinedButton(onClick = onRefresh, enabled = !loading) {
            DesktopButtonContent(text = if (loading) "Refreshing" else "Refresh", icon = Icons.Outlined.Refresh)
        }
    }
}

@Composable
private fun SkillRow(
    skill: Skill,
    installed: Boolean,
    canManage: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Outlined.Bolt,
                contentDescription = null,
                tint = if (installed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp).size(18.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = skill.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    skill.version?.takeIf { it.isNotBlank() }?.let { Pill("v$it", MaterialTheme.colorScheme.secondary) }
                    if (installed) Pill("installed", MaterialTheme.colorScheme.primary)
                }
                skill.description?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (skill.tags.isNotEmpty() || skill.author != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        skill.author?.takeIf { it.isNotBlank() }?.let { Pill("@$it", MaterialTheme.colorScheme.tertiary) }
                        skill.tags.take(3).forEach { Pill(it, MaterialTheme.colorScheme.secondary) }
                    }
                }
            }
            SkillActionButton(installed, canManage, onInstall, onUninstall)
        }
    }
}

@Composable
private fun SkillActionButton(
    installed: Boolean,
    canManage: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    if (installed) {
        DesktopOutlinedButton(onClick = onUninstall, enabled = canManage) {
            DesktopButtonContent(text = "Remove", icon = Icons.Outlined.Close)
        }
    } else {
        DesktopDefaultButton(onClick = onInstall, enabled = canManage) {
            DesktopButtonContent(text = "Install", icon = Icons.Outlined.Check)
        }
    }
}

@Composable
private fun SkillDetailPanel(
    skill: Skill,
    installed: Boolean,
    canManage: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        modifier = Modifier.width(360.dp).fillMaxHeight(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(22.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = skill.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Box(modifier = Modifier.size(28.dp).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close", modifier = Modifier.size(16.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                skill.version?.takeIf { it.isNotBlank() }?.let { Pill("v$it", MaterialTheme.colorScheme.secondary) }
                skill.author?.takeIf { it.isNotBlank() }?.let { Pill("@$it", MaterialTheme.colorScheme.tertiary) }
                skill.installedCount?.let { Pill("$it installs", MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            skill.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (skill.tags.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    skill.tags.forEach { Pill(it, MaterialTheme.colorScheme.secondary) }
                }
            }
            SkillActionButton(installed, canManage, onInstall, onUninstall)
            if (!canManage) {
                Text(
                    "Select an agent to install or remove skills.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun InfoCard(message: String, isError: Boolean = false) {
    Surface(
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.medium,
        border = if (isError) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text = message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun Pill(text: String, color: Color) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.12f),
        contentColor = color,
        border = BorderStroke(1.dp, color.copy(alpha = 0.18f)),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}
