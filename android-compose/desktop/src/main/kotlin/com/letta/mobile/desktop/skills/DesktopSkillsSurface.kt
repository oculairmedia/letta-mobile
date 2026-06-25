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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.skills.Skill
import com.letta.mobile.data.skills.SkillCategories
import com.letta.mobile.data.skills.SkillCategory
import com.letta.mobile.desktop.DesktopButtonContent
import com.letta.mobile.desktop.DesktopDefaultButton
import com.letta.mobile.desktop.DesktopInlineError
import com.letta.mobile.desktop.DesktopOutlinedButton
import com.letta.mobile.desktop.DesktopTextField
import com.letta.mobile.desktop.tools.DesktopToolLibraryState
import com.letta.mobile.ui.theme.customColors

private enum class SkillsTab(val label: String) { Skills("Skills"), Tools("Tools") }

/**
 * Unified Skills & Tools surface. A single header carries the title with the
 * Skills/Tools toggle (and the skills sub-filters) UNDERNEATH it, and both tabs
 * render the same category-grouped 2-column card grid so they read as one
 * coherent page (matching the design mockups + the Schedules/Memory layout
 * conventions). Radii come from the desktop shape tokens.
 */
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
    var assignedOnly by remember { mutableStateOf(false) }
    var skillQuery by remember { mutableStateOf("") }
    var selectedSkill by remember { mutableStateOf<Skill?>(null) }

    Column(modifier = modifier.fillMaxHeight().background(MaterialTheme.colorScheme.background)) {
        SkillsToolsHeader(
            tab = tab,
            onTab = { tab = it; selectedSkill = null },
            assignedOnly = assignedOnly,
            onAssigned = { assignedOnly = it },
            installedCount = installedSkillNames.size,
            query = if (tab == SkillsTab.Skills) skillQuery else toolState.searchQuery,
            onQuery = { q -> if (tab == SkillsTab.Skills) skillQuery = q else onToolsSearchQueryChanged(q) },
            loading = if (tab == SkillsTab.Skills) skillsLoading else toolState.isLoading,
            onRefresh = { if (tab == SkillsTab.Skills) onRefreshSkills() else onToolsRefresh() },
        )
        when (tab) {
            SkillsTab.Skills -> SkillsContent(
                skills = skills,
                installedSkillNames = installedSkillNames,
                skillsLoading = skillsLoading,
                skillsError = skillsError,
                canManage = canManageSkills,
                focusedAgentName = focusedAgentName,
                assignedOnly = assignedOnly,
                query = skillQuery,
                selectedSkill = selectedSkill,
                onSelect = { selectedSkill = it },
                onClose = { selectedSkill = null },
                onInstall = onInstallSkill,
                onUninstall = onUninstallSkill,
            )
            SkillsTab.Tools -> ToolsContent(
                toolState = toolState,
                onRefresh = onToolsRefresh,
                onLoadMore = onToolsLoadMore,
            )
        }
    }
}

// --- Header -----------------------------------------------------------------

@Composable
private fun SkillsToolsHeader(
    tab: SkillsTab,
    onTab: (SkillsTab) -> Unit,
    assignedOnly: Boolean,
    onAssigned: (Boolean) -> Unit,
    installedCount: Int,
    query: String,
    onQuery: (String) -> Unit,
    loading: Boolean,
    onRefresh: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 16.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Skills & Tools", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Box(Modifier.width(220.dp)) {
                DesktopTextField(
                    value = query,
                    onValueChange = onQuery,
                    placeholder = if (tab == SkillsTab.Skills) "Search skills" else "Search tools",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            DesktopOutlinedButton(onClick = onRefresh, enabled = !loading) {
                DesktopButtonContent(text = if (loading) "Refreshing" else "Refresh", icon = Icons.Outlined.Refresh)
            }
        }
        // Toggle + sub-filters live on their own row, beneath the title.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SegChip("Skills", tab == SkillsTab.Skills) { onTab(SkillsTab.Skills) }
            SegChip("Tools", tab == SkillsTab.Tools) { onTab(SkillsTab.Tools) }
            if (tab == SkillsTab.Skills) {
                Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(20.dp).background(MaterialTheme.colorScheme.outlineVariant))
                SegChip("All skills", !assignedOnly) { onAssigned(false) }
                SegChip("Assigned · $installedCount", assignedOnly) { onAssigned(true) }
            }
        }
    }
}

@Composable
private fun SegChip(text: String, active: Boolean, onClick: () -> Unit) {
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

// --- Skills content ---------------------------------------------------------

@Composable
private fun SkillsContent(
    skills: List<Skill>,
    installedSkillNames: Set<String>,
    skillsLoading: Boolean,
    skillsError: String?,
    canManage: Boolean,
    focusedAgentName: String?,
    assignedOnly: Boolean,
    query: String,
    selectedSkill: Skill?,
    onSelect: (Skill) -> Unit,
    onClose: () -> Unit,
    onInstall: (String) -> Unit,
    onUninstall: (String) -> Unit,
) {
    val installedExtras = remember(installedSkillNames, skills) {
        val known = skills.map { it.name }.toSet()
        installedSkillNames.filter { it !in known }.map { Skill(name = it) }
    }
    val all = remember(skills, installedExtras) { skills + installedExtras }
    val base = if (assignedOnly) all.filter { it.name in installedSkillNames } else all
    val filtered = remember(base, query) {
        if (query.isBlank()) base
        else base.filter { it.name.contains(query, true) || it.description?.contains(query, true) == true }
    }
    val grouped = remember(filtered) { SkillCategories.grouped(filtered, nameOf = { it.name }, tagsOf = { it.tags }) }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            skillsError?.let {
                Box(Modifier.padding(horizontal = 32.dp, vertical = 4.dp)) {
                    DesktopInlineError(message = it, onRetry = {}, retrying = skillsLoading)
                }
            }
            when {
                skillsLoading && skills.isEmpty() -> InfoBox("Loading skills from the active backend.")
                filtered.isEmpty() -> InfoBox(
                    if (assignedOnly) "No skills assigned to ${focusedAgentName ?: "this agent"} yet." else "No skills match your search.",
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(vertical = 16.dp),
                ) {
                    categoryGrid(grouped, keyOf = { it.name }) { skill, cardModifier ->
                        CatalogCard(
                            name = skill.name,
                            description = skill.description,
                            accent = SkillCategories.categorize(skill.name, skill.tags).accentColor(),
                            onClick = { onSelect(skill) },
                            modifier = cardModifier,
                        ) {
                            SkillAddButton(
                                installed = skill.name in installedSkillNames,
                                canManage = canManage,
                                onInstall = { onInstall(skill.name) },
                                onUninstall = { onUninstall(skill.name) },
                            )
                        }
                    }
                }
            }
        }
        selectedSkill?.let { skill ->
            SkillDetailPanel(
                skill = skill,
                installed = skill.name in installedSkillNames,
                canManage = canManage,
                onInstall = { onInstall(skill.name) },
                onUninstall = { onUninstall(skill.name) },
                onClose = onClose,
            )
        }
    }
}

// --- Tools content ----------------------------------------------------------

@Composable
private fun ToolsContent(
    toolState: DesktopToolLibraryState,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
) {
    val tools = toolState.filteredTools
    val grouped = remember(tools) { SkillCategories.grouped(tools, nameOf = { it.name }, tagsOf = { it.tags }) }

    Column(Modifier.fillMaxSize()) {
        toolState.errorMessage?.let {
            Box(Modifier.padding(horizontal = 32.dp, vertical = 4.dp)) {
                DesktopInlineError(message = it, onRetry = onRefresh, retrying = toolState.isLoading)
            }
        }
        when {
            toolState.isLoading && tools.isEmpty() -> InfoBox("Loading tools from the active backend.")
            tools.isEmpty() -> InfoBox("No tools match your search.")
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
            ) {
                categoryGrid(grouped, keyOf = { it.name }) { tool, cardModifier ->
                    CatalogCard(
                        name = tool.name,
                        description = tool.description,
                        accent = SkillCategories.categorize(tool.name, tool.tags).accentColor(),
                        onClick = {},
                        modifier = cardModifier,
                    ) {
                        tool.toolType?.takeIf { it.isNotBlank() }?.let { Pill(it, MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                item("load-more") {
                    if (toolState.isLoadingMore) {
                        InfoBox("Loading more tools…")
                    } else {
                        Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
                            DesktopOutlinedButton(onClick = onLoadMore) { DesktopButtonContent("Load more") }
                        }
                    }
                }
            }
        }
    }
}

// --- Shared grid + card -----------------------------------------------------

private fun <T> LazyListScope.categoryGrid(
    grouped: List<Pair<SkillCategory, List<T>>>,
    keyOf: (T) -> String,
    card: @Composable (T, Modifier) -> Unit,
) {
    grouped.forEach { (category, items) ->
        item(key = "section-${category.name}") {
            Text(
                text = category.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(items = items.chunked(2), key = { keyOf(it.first()) }) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { item -> card(item, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CatalogCard(
    name: String,
    description: String?,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
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
            Text(name.firstOrNull()?.uppercase() ?: "?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = accent)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
            description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing()
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
