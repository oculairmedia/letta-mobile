package com.letta.mobile.ui.shell.pages.skills

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.skills.Skill
import com.letta.mobile.data.skills.SkillCategories
import com.letta.mobile.data.skills.SkillCategory
import com.letta.mobile.data.skills.SkillSourceLabels
import com.letta.mobile.ui.components.LettaCatalogCard
import com.letta.mobile.ui.components.LettaChipTab
import com.letta.mobile.ui.components.LettaInfoBox
import com.letta.mobile.ui.components.LettaPill
import com.letta.mobile.ui.components.LettaRefreshAction
import com.letta.mobile.ui.host.LettaPane
import com.letta.mobile.ui.host.LettaPaneKind
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.customColors

@Composable
internal fun SkillsHeader(page: SkillsPageScope) {
    val horizontal = if (page.wide) LettaDimens.Space.xxl else LettaDimens.Space.lg
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = horizontal, end = horizontal, top = LettaDimens.Space.lg, bottom = LettaDimens.Space.sm),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (page.options.showTitle) {
                    Text(PAGE_TITLE, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
            }
            if (page.wide) SkillsSearch(page, Modifier.width(LettaDimens.Pane.navWidth))
            // Pointer only: touch pulls the list (SkillsPage's LettaRefreshable).
            LettaRefreshAction(
                refreshing = page.refreshing,
                onRefresh = page::refresh,
                modifier = Modifier.testTag(SkillsPageTags.REFRESH),
            ) { onRefresh, modifier -> page.options.slots.refreshAction.Content(onRefresh, modifier) }
        }
        if (!page.wide) SkillsSearch(page, Modifier.fillMaxWidth())
        SkillsHeaderChips(page)
    }
}

@Composable
private fun SkillsSearch(page: SkillsPageScope, modifier: Modifier) {
    page.options.slots.searchField.Content(
        query = page.filter.query,
        onQueryChange = page.controls.onQueryChange,
        placeholder = SEARCH_PLACEHOLDER,
        modifier = modifier.testTag(SkillsPageTags.SEARCH),
    )
}

/** Jump to Skills / Tools, then the skills filter (every skill, or only the agent's). */
@Composable
private fun SkillsHeaderChips(page: SkillsPageScope) {
    val chipModifier = if (page.options.touch) Modifier.minimumInteractiveComponentSize() else Modifier
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkillsPageSection.entries.forEach { section ->
            LettaChipTab(section.label, false, { page.controls.onJumpTo(section) }, chipModifier.testTag(SkillsPageTags.jump(section)))
        }
        Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
        val assignedOnly = page.filter.assignedOnly
        LettaChipTab(ALL_SKILLS, !assignedOnly, { page.controls.onAssignedOnlyChange(false) }, chipModifier.testTag(SkillsPageTags.ALL_SKILLS))
        LettaChipTab(
            "Assigned · ${page.state.installedSkillNames.size}",
            assignedOnly,
            { page.controls.onAssignedOnlyChange(true) },
            chipModifier.testTag(SkillsPageTags.ASSIGNED_ONLY),
        )
    }
}

@Composable
internal fun SkillsPageRowContent(row: SkillsPageRow, page: SkillsPageScope) {
    when (row) {
        is SkillsPageRow.SectionHeader -> SectionHeader(row, page)
        is SkillsPageRow.Info -> LettaInfoBox(row.message, Modifier.testTag(row.key), contentPadding = NoPadding)
        is SkillsPageRow.ActionError -> ActionErrorNotice(row.message, page)
        is SkillsPageRow.Group -> Text(
            row.label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is SkillsPageRow.SkillCards -> CardRow(row.skills, page) { skill, modifier -> SkillCard(skill, page, modifier) }
        is SkillsPageRow.ToolCards -> CardRow(row.tools, page) { tool, modifier -> ToolCard(tool, page, modifier) }
        is SkillsPageRow.ToolTags -> ToolTagChips(row, page)
        is SkillsPageRow.LoadMoreTools -> LoadMoreTools(row, page)
    }
}

/** A failed install / remove, with its own dismiss; the skills stay listed beneath it. */
@Composable
private fun ActionErrorNotice(message: String, page: SkillsPageScope) {
    Row(Modifier.fillMaxWidth().testTag(SkillsPageTags.ACTION_ERROR), verticalAlignment = Alignment.CenterVertically) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
        IconButton(onClick = page.actions.onDismissSkillActionError) { Icon(Icons.Outlined.Close, contentDescription = DISMISS) }
    }
}

@Composable
private fun SectionHeader(row: SkillsPageRow.SectionHeader, page: SkillsPageScope) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = LettaDimens.Space.sm).testTag(SkillsPageTags.section(row.section)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${row.section.label} · ${row.count}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        val onAddTool = page.actions.onAddTool
        if (row.section == SkillsPageSection.Tools && onAddTool != null) {
            IconButton(onClick = onAddTool, modifier = Modifier.testTag(SkillsPageTags.ADD_TOOL)) {
                Icon(Icons.Outlined.Add, contentDescription = CREATE_TOOL)
            }
        }
    }
}

@Composable
private fun <T> CardRow(items: List<T>, page: SkillsPageScope, card: @Composable (T, Modifier) -> Unit) {
    val columns = if (page.wide) 2 else 1
    Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.lg)) {
        items.forEach { item -> card(item, Modifier.weight(1f)) }
        repeat(columns - items.size) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
private fun SkillCard(skill: Skill, page: SkillsPageScope, modifier: Modifier) {
    LettaCatalogCard(
        title = skill.name,
        description = skill.description,
        accent = SkillCategories.categorize(skill.name, skill.tags).accentColor(),
        onClick = { page.controls.onSelectSkill(skill.name) },
        modifier = modifier.testTag(SkillsPageTags.skill(skill.name)),
        trailing = { SkillControl(skill, page) },
    )
}

/**
 * A skill letta-code can't turn off (anything but a symlink in its skills folder) shows its source,
 * not a toggle: a control that can only fail is never offered.
 */
@Composable
private fun SkillControl(skill: Skill, page: SkillsPageScope) {
    if (skill.isReadOnly(page)) {
        LettaPill(
            SkillSourceLabels.label(skill.source),
            MaterialTheme.colorScheme.onSurfaceVariant,
            Modifier.testTag(SkillsPageTags.skillSource(skill.name)),
        )
    } else {
        SkillToggle(skill, page)
    }
}

private fun Skill.isReadOnly(page: SkillsPageScope): Boolean = name in page.state.installedSkillNames && !controllable

/** The card's add / check button: installs or removes the skill on the agent. */
@Composable
private fun SkillToggle(skill: Skill, page: SkillsPageScope) {
    val installed = skill.name in page.state.installedSkillNames
    val canManage = page.state.canManageSkills
    val background = if (installed) MaterialTheme.colorScheme.primary.copy(alpha = INSTALLED_ALPHA) else MaterialTheme.colorScheme.surfaceContainerHighest
    // On touch the 28dp button keeps its look but reaches the 48dp touch target.
    val target = if (page.options.touch) Modifier.minimumInteractiveComponentSize() else Modifier
    Box(
        target.size(LettaDimens.Control.iconButton).clip(MaterialTheme.shapes.small).background(background)
            .clickable(enabled = canManage) { page.toggle(skill.name, installed) }
            .testTag(SkillsPageTags.skillToggle(skill.name)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (installed) Icons.Outlined.Check else Icons.Outlined.Add,
            contentDescription = if (installed) "Remove skill" else "Add skill",
            tint = if (installed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(LettaDimens.Control.icon),
        )
    }
}

private fun SkillsPageScope.toggle(name: String, installed: Boolean) {
    if (installed) actions.onUninstallSkill(name) else actions.onInstallSkill(name)
}

@Composable
private fun ToolCard(tool: Tool, page: SkillsPageScope, modifier: Modifier) {
    LettaCatalogCard(
        title = tool.name,
        description = tool.description,
        accent = SkillCategories.categorize(tool.name, tool.tags).accentColor(),
        onClick = { page.controls.onSelectTool(tool) },
        modifier = modifier.testTag(SkillsPageTags.tool(tool.id.value)),
        trailing = { tool.toolType?.takeIf { it.isNotBlank() }?.let { LettaPill(it, MaterialTheme.colorScheme.onSurfaceVariant) } },
    )
}

@Composable
private fun ToolTagChips(row: SkillsPageRow.ToolTags, page: SkillsPageScope) {
    val chipModifier = if (page.options.touch) Modifier.minimumInteractiveComponentSize() else Modifier
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LettaChipTab(ALL_TOOLS, row.selected.isEmpty(), page.actions.onClearToolTags, chipModifier)
        row.tags.forEach { tag -> LettaChipTab(tag, tag in row.selected, { page.actions.onToolTagToggled(tag) }, chipModifier) }
    }
}

@Composable
private fun LoadMoreTools(row: SkillsPageRow.LoadMoreTools, page: SkillsPageScope) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (row.loading) {
            Text(LOADING_MORE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            OutlinedButton(onClick = page.actions.onLoadMoreTools, modifier = Modifier.testTag(SkillsPageTags.LOAD_MORE_TOOLS)) {
                Text(LOAD_MORE)
            }
        }
    }
}

/** A skill's detail: the docked panel on wide windows, the sheet's body on phones. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SkillDetail(skill: Skill, page: SkillsPageScope) {
    val installed = skill.name in page.state.installedSkillNames
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(LettaDimens.Space.lg).testTag(SkillsPageTags.DETAIL),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(skill.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton(onClick = { page.controls.onSelectSkill(null) }, modifier = Modifier.testTag(SkillsPageTags.DETAIL_CLOSE)) {
                Icon(Icons.Outlined.Close, contentDescription = CLOSE)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm), verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
            skill.version?.takeIf { it.isNotBlank() }?.let { LettaPill("v$it", MaterialTheme.colorScheme.secondary) }
            skill.author?.takeIf { it.isNotBlank() }?.let { LettaPill("@$it", MaterialTheme.colorScheme.tertiary) }
            skill.installedCount?.let { LettaPill("$it installs", MaterialTheme.colorScheme.onSurfaceVariant) }
            skill.tags.forEach { LettaPill(it, MaterialTheme.colorScheme.secondary) }
        }
        skill.description?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SkillDetailAction(skill, installed, page)
    }
}

@Composable
private fun SkillDetailAction(skill: Skill, installed: Boolean, page: SkillsPageScope) {
    if (skill.isReadOnly(page)) {
        LettaPill(SkillSourceLabels.label(skill.source), MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            SkillSourceLabels.readOnlyHint(skill.source),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val canManage = page.state.canManageSkills
    val hint = if (canManage) null else SELECT_AGENT_HINT
    if (installed) {
        OutlinedButton(onClick = { page.toggle(skill.name, installed = true) }, enabled = hint == null) { Text(REMOVE) }
    } else {
        Button(onClick = { page.toggle(skill.name, installed = false) }, enabled = canManage) { Text(INSTALL) }
    }
    hint?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
internal fun SkillDetailSheet(skill: Skill, page: SkillsPageScope) {
    LettaPane(LettaPaneKind.Modal, onDismiss = { page.controls.onSelectSkill(null) }) {
        SkillDetail(skill, page)
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

private val NoPadding = PaddingValues()
private const val INSTALLED_ALPHA = 0.16f
private const val PAGE_TITLE = "Skills"
private const val SEARCH_PLACEHOLDER = "Search skills and tools"
private const val ALL_SKILLS = "All skills"
private const val ALL_TOOLS = "All tools"
private const val CREATE_TOOL = "Create tool"
private const val LOAD_MORE = "Load more tools"
private const val LOADING_MORE = "Loading more tools…"
private const val CLOSE = "Close"
private const val INSTALL = "Install"
private const val REMOVE = "Remove"
private const val SELECT_AGENT_HINT = "Open an agent to install or remove skills."
private const val DISMISS = "Dismiss"
