package com.letta.mobile.desktop.skills

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.skills.Skill
import com.letta.mobile.data.skills.SkillCategories
import com.letta.mobile.data.skills.SkillCategory
import com.letta.mobile.desktop.DesktopButtonContent
import com.letta.mobile.desktop.DesktopControlText
import com.letta.mobile.desktop.DesktopDefaultButton
import com.letta.mobile.desktop.DesktopInlineError
import com.letta.mobile.desktop.DesktopOutlinedButton
import com.letta.mobile.desktop.DesktopRadioChip
import com.letta.mobile.desktop.DesktopTextField
import com.letta.mobile.desktop.tools.DesktopToolLibrarySurface
import com.letta.mobile.desktop.tools.DesktopToolLibraryState
import com.letta.mobile.ui.theme.customColors

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
    Column(modifier = modifier.fillMaxHeight().background(MaterialTheme.colorScheme.background)) {
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
    var assignedOnly by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var selectedSkill by remember { mutableStateOf<Skill?>(null) }

    // The agent's installed list may include skills not present in the registry.
    val installedExtras = remember(installedSkillNames, skills) {
        val known = skills.map { it.name }.toSet()
        installedSkillNames.filter { it !in known }.map { Skill(name = it) }
    }
    val all = remember(skills, installedExtras) { skills + installedExtras }
    val base = if (assignedOnly) all.filter { it.name in installedSkillNames } else all
    val filtered = remember(base, query) {
        if (query.isBlank()) {
            base
        } else {
            base.filter { it.name.contains(query, ignoreCase = true) || it.description?.contains(query, ignoreCase = true) == true }
        }
    }
    val grouped = remember(filtered) {
        SkillCategories.grouped(filtered, nameOf = { it.name }, tagsOf = { it.tags })
    }

    Row(modifier = modifier) {
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            SkillsHeader(
                installedCount = installedSkillNames.size,
                totalCount = skills.size,
                assignedOnly = assignedOnly,
                onSelectAll = { assignedOnly = false },
                onSelectAssigned = { assignedOnly = true },
                query = query,
                onQuery = { query = it },
                loading = skillsLoading,
                onRefresh = onRefreshSkills,
            )
            skillsError?.let {
                Box(Modifier.padding(horizontal = 32.dp, vertical = 4.dp)) {
                    DesktopInlineError(message = it, onRetry = onRefreshSkills, retrying = skillsLoading)
                }
            }
            when {
                skillsLoading && skills.isEmpty() -> InfoBox("Loading skills from the active backend.")
                filtered.isEmpty() -> InfoBox(
                    if (assignedOnly) {
                        "No skills assigned to ${focusedAgentName ?: "this agent"} yet."
                    } else {
                        "No skills match your search."
                    },
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(vertical = 16.dp),
                ) {
                    grouped.forEach { (category, sectionSkills) ->
                        item(key = "section-${category.name}") {
                            Text(
                                text = category.label,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        items(items = sectionSkills.chunked(2), key = { it.first().name }) { rowSkills ->
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                rowSkills.forEach { skill ->
                                    SkillCard(
                                        skill = skill,
                                        installed = skill.name in installedSkillNames,
                                        canManage = canManageSkills,
                                        onClick = { selectedSkill = skill },
                                        onInstall = { onInstallSkill(skill.name) },
                                        onUninstall = { onUninstallSkill(skill.name) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                if (rowSkills.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
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
    assignedOnly: Boolean,
    onSelectAll: () -> Unit,
    onSelectAssigned: () -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    loading: Boolean,
    onRefresh: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 12.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Skills", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Box(Modifier.width(220.dp)) {
                DesktopTextField(value = query, onValueChange = onQuery, placeholder = "Search skills", modifier = Modifier.fillMaxWidth())
            }
            DesktopOutlinedButton(onClick = onRefresh, enabled = !loading) {
                DesktopButtonContent(text = if (loading) "Refreshing" else "Refresh", icon = Icons.Outlined.Refresh)
            }
        }
        // Filter chips sit on their own row, underneath the title.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HeaderTab("All skills", !assignedOnly, onSelectAll)
            HeaderTab("Assigned · $installedCount", assignedOnly, onSelectAssigned)
        }
    }
}

@Composable
private fun HeaderTab(text: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(if (active) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun SkillCard(
    skill: Skill,
    installed: Boolean,
    canManage: Boolean,
    onClick: () -> Unit,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = SkillCategories.categorize(skill.name, skill.tags).accentColor()
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).clip(MaterialTheme.shapes.small).background(accent.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                skill.name.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = accent,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(skill.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
            skill.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        SkillAddButton(installed, canManage, onInstall, onUninstall)
    }
}

@Composable
private fun SkillAddButton(installed: Boolean, canManage: Boolean, onInstall: () -> Unit, onUninstall: () -> Unit) {
    val bg = if (installed) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHighest
    Box(
        Modifier.size(30.dp).clip(MaterialTheme.shapes.small).background(bg)
            .clickable(enabled = canManage) { if (installed) onUninstall() else onInstall() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (installed) Icons.Outlined.Check else Icons.Outlined.Add,
            contentDescription = if (installed) "Remove skill" else "Add skill",
            tint = if (installed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun InfoBox(message: String) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 12.dp)) {
        InfoCard(message)
    }
}

@Composable
private fun SkillCategory.accentColor(): Color = when (this) {
    SkillCategory.Developer -> MaterialTheme.customColors.agentBColor
    SkillCategory.Productivity -> MaterialTheme.colorScheme.primary
    SkillCategory.Data -> MaterialTheme.customColors.runningColor
    SkillCategory.Design -> MaterialTheme.customColors.agentCColor
    SkillCategory.Communication -> MaterialTheme.customColors.agentAColor
    SkillCategory.Automation -> MaterialTheme.customColors.successColor
    SkillCategory.Other -> MaterialTheme.colorScheme.onSurfaceVariant
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
