package com.letta.mobile.ui.shell.pages.skills

import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.skills.Skill
import com.letta.mobile.data.skills.SkillCategories
import com.letta.mobile.data.skills.SkillsPageFilter
import com.letta.mobile.data.tools.ToolLibraryFilter

/**
 * One row of the Skills page's list. Keys are prefixed per section, so a "Developer" group under
 * Skills and one under Tools never collide.
 */
internal sealed interface SkillsPageRow {
    val key: String

    data class SectionHeader(val section: SkillsPageSection, val count: Int) : SkillsPageRow {
        override val key: String get() = "header-${section.name}"
    }

    data class Info(override val key: String, val message: String) : SkillsPageRow

    data class Group(val section: SkillsPageSection, val label: String) : SkillsPageRow {
        override val key: String get() = "group-${section.name}-$label"
    }

    data class SkillCards(val skills: List<Skill>) : SkillsPageRow {
        override val key: String get() = "skills-${skills.first().name}"
    }

    data class ToolCards(val tools: List<Tool>) : SkillsPageRow {
        override val key: String get() = "tools-${tools.first().id.value}"
    }

    data class ToolTags(val tags: List<String>, val selected: Set<String>) : SkillsPageRow {
        override val key: String get() = "tool-tags"
    }

    data class LoadMoreTools(val loading: Boolean) : SkillsPageRow {
        override val key: String get() = "tools-load-more"
    }
}

/** Builds the page's rows: the Skills section, then the Tools section, both narrowed by one query. */
internal object SkillsPageRows {
    fun build(state: SkillsPageState, filter: SkillsPageQuery, columns: Int): List<SkillsPageRow> =
        skillsSection(state, filter, columns) + toolsSection(state, filter, columns)

    /** Where [section]'s header sits, for the jump chips and the initial scroll. */
    fun indexOf(rows: List<SkillsPageRow>, section: SkillsPageSection): Int =
        rows.indexOfFirst { it is SkillsPageRow.SectionHeader && it.section == section }

    fun visibleSkills(state: SkillsPageState, filter: SkillsPageQuery): List<Skill> =
        SkillsPageFilter.skills(state.skills, state.installedSkillNames, filter.query, filter.assignedOnly)

    fun visibleTools(state: SkillsPageState, filter: SkillsPageQuery): List<Tool> =
        ToolLibraryFilter.filter(state.tools.tools, filter.query, state.tools.selectedTags)

    private fun skillsSection(state: SkillsPageState, filter: SkillsPageQuery, columns: Int): List<SkillsPageRow> = buildList {
        val skills = visibleSkills(state, filter)
        add(SkillsPageRow.SectionHeader(SkillsPageSection.Skills, skills.size))
        state.skillsError?.let { add(SkillsPageRow.Info("skills-error", "Couldn't load skills: $it")) }
        when {
            state.skillsLoading && state.skills.isEmpty() -> add(SkillsPageRow.Info("skills-loading", "Loading skills from the active backend."))
            skills.isEmpty() -> add(SkillsPageRow.Info("skills-empty", emptySkillsMessage(state, filter)))
            else -> SkillCategories.grouped(skills, nameOf = { it.name }, tagsOf = { it.tags }).forEach { (category, inGroup) ->
                add(SkillsPageRow.Group(SkillsPageSection.Skills, category.label))
                inGroup.chunked(columns).forEach { add(SkillsPageRow.SkillCards(it)) }
            }
        }
    }

    private fun toolsSection(state: SkillsPageState, filter: SkillsPageQuery, columns: Int): List<SkillsPageRow> = buildList {
        val library = state.tools
        val tools = visibleTools(state, filter)
        add(SkillsPageRow.SectionHeader(SkillsPageSection.Tools, tools.size))
        library.errorMessage?.let { add(SkillsPageRow.Info("tools-error", "Couldn't load tools: $it")) }
        if (library.allTags.isNotEmpty()) add(SkillsPageRow.ToolTags(library.allTags, library.selectedTags))
        if (library.isLoadingMcpTools) add(SkillsPageRow.Info("tools-mcp-loading", "Loading MCP server tools…"))
        when {
            library.isLoading && library.tools.isEmpty() -> add(SkillsPageRow.Info("tools-loading", "Loading tools from the active backend."))
            tools.isEmpty() -> add(SkillsPageRow.Info("tools-empty", emptyToolsMessage(filter)))
            else -> SkillCategories.grouped(tools, nameOf = { it.name }, tagsOf = { it.tags }).forEach { (category, inGroup) ->
                add(SkillsPageRow.Group(SkillsPageSection.Tools, category.label))
                inGroup.chunked(columns).forEach { add(SkillsPageRow.ToolCards(it)) }
            }
        }
        // Paging only while no search narrows the list: otherwise it fetches unrelated pages.
        if (library.hasMorePages && library.tools.isNotEmpty() && filter.query.isBlank()) {
            add(SkillsPageRow.LoadMoreTools(library.isLoadingMore))
        }
    }

    private fun emptySkillsMessage(state: SkillsPageState, filter: SkillsPageQuery): String = when {
        filter.query.isNotBlank() -> "No skills match \"${filter.query.trim()}\"."
        filter.assignedOnly -> "No skills assigned to ${state.focusedAgentName ?: "this agent"} yet."
        else -> "No skills are available on the active backend."
    }

    private fun emptyToolsMessage(filter: SkillsPageQuery): String =
        if (filter.query.isNotBlank()) "No tools match \"${filter.query.trim()}\"." else "No tools yet."
}
