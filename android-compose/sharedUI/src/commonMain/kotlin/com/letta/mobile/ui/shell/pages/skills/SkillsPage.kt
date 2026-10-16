package com.letta.mobile.ui.shell.pages.skills

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.skills.Skill
import com.letta.mobile.data.tools.ToolLibraryState
import com.letta.mobile.ui.components.LettaRefreshable
import com.letta.mobile.ui.shell.pages.channels.ChannelsPageSlots
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The Skills page shared by desktop and Android (letta-mobile-zxk2n.32): the agent's skills and,
 * beneath them, the tools it can call - one page, since a tool is a skill's smaller sibling. A
 * single search narrows both sections; the skills carry add / remove, the tools section the host's
 * create action when it has one ([SkillsPageActions.onAddTool]).
 *
 * Hosts own the navigation chrome and the data (sharedLogic's SkillsCatalogController and
 * ToolLibraryController); [SkillsPageOptions.initialSection] opens the page scrolled to Tools, which
 * is how the standalone Tools route renders. At or above [LettaDimens.Pane.wideBreakpoint] cards
 * lay out two to a row and a skill's detail docks beside them; below it they stack and the detail
 * rises as a bottom sheet.
 */
@Composable
fun SkillsPage(
    state: SkillsPageState,
    actions: SkillsPageActions,
    modifier: Modifier = Modifier,
    options: SkillsPageOptions = SkillsPageOptions(),
) {
    var query by rememberSaveable { mutableStateOf("") }
    var assignedOnly by rememberSaveable { mutableStateOf(false) }
    var selectedSkillName by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedToolId by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag(SkillsPageTags.PAGE)) {
        val wide = maxWidth >= LettaDimens.Pane.wideBreakpoint
        val filter = SkillsPageQuery(query = query, assignedOnly = assignedOnly)
        val rows = remember(state, filter, wide) { SkillsPageRows.build(state, filter, columns = if (wide) 2 else 1) }
        val page = SkillsPageScope(
            state = state,
            actions = actions,
            options = options,
            wide = wide,
            filter = filter,
            rows = rows,
            listState = listState,
            controls = SkillsPageControls(
                onQueryChange = { query = it },
                onAssignedOnlyChange = { assignedOnly = it },
                onSelectSkill = { selectedSkillName = it },
                onJumpTo = { section -> scope.jumpTo(listState, rows, section) },
                onSelectTool = { tool ->
                    selectedToolId = tool.id.value
                    actions.onToolClick?.invoke(tool)
                },
            ),
            selectedSkill = selectedSkillName?.let { name -> state.skillNamed(name) },
        )
        InitialSectionJump(page)
        if (wide) WideSkillsLayout(page) else CompactSkillsLayout(page)
    }
    selectedToolId?.let { id -> state.tools.tools.firstOrNull { it.id.value == id } }?.let { tool ->
        ToolDetailPane(tool, onClose = { selectedToolId = null })
    }
}

/** Which section the page opens on; [Tools] is the standalone Tools route. */
enum class SkillsPageSection(val label: String) { Skills("Skills"), Tools("Tools") }

/** Everything the page shows: the skills catalog, the tool library and the agent they belong to. */
@Immutable
data class SkillsPageState(
    val skills: List<Skill> = emptyList(),
    val installedSkillNames: Set<String> = emptySet(),
    val skillsLoading: Boolean = false,
    val skillsError: String? = null,
    /** A failed install / remove: a dismissible notice above the (still listed) skills. */
    val skillActionError: String? = null,
    /** False hides add / remove (no skill API, or no agent to install onto). */
    val canManageSkills: Boolean = false,
    val focusedAgentName: String? = null,
    val tools: ToolLibraryState = ToolLibraryState(isLoading = false),
) {
    internal fun skillNamed(name: String): Skill? =
        skills.firstOrNull { it.name == name } ?: Skill(name = name).takeIf { name in installedSkillNames }
}

/**
 * What the page reports back. A tool card always opens the tool's read-only detail; [onToolClick]
 * also tells the host. [onAddTool] null hides the Tools section's create button (every App Server
 * host: letta-code tools are code-defined).
 */
@Immutable
data class SkillsPageActions(
    val onRefreshSkills: () -> Unit = {},
    val onInstallSkill: (String) -> Unit = {},
    val onUninstallSkill: (String) -> Unit = {},
    val onDismissSkillActionError: () -> Unit = {},
    val onRefreshTools: () -> Unit = {},
    val onToolTagToggled: (String) -> Unit = {},
    val onClearToolTags: () -> Unit = {},
    val onLoadMoreTools: () -> Unit = {},
    val onToolClick: ((Tool) -> Unit)? = null,
    val onAddTool: (() -> Unit)? = null,
)

/**
 * Host presentation choices: [showTitle] false when the host already titles the screen; [touch]
 * grows the chips to touch-target size; [initialSection] is where the page opens; [slots] are the
 * host's own search field and refresh action (the same catalog-header slots as Channels).
 */
@Immutable
data class SkillsPageOptions(
    val showTitle: Boolean = true,
    val touch: Boolean = false,
    val initialSection: SkillsPageSection = SkillsPageSection.Skills,
    val slots: ChannelsPageSlots = ChannelsPageSlots(),
)

/** Test tags for the shared Skills page. */
object SkillsPageTags {
    const val PAGE = "skills_page"
    const val LIST = "skills_list"
    const val SEARCH = "skills_search"
    const val REFRESH = "skills_refresh"
    const val ADD_TOOL = "skills_add_tool"
    const val DETAIL = "skills_detail"
    const val DETAIL_CLOSE = "skills_detail_close"
    const val ASSIGNED_ONLY = "skills_assigned_only"
    const val ALL_SKILLS = "skills_all"
    const val LOAD_MORE_TOOLS = "skills_load_more_tools"
    const val TOOL_DETAIL = "skills_tool_detail"
    const val ACTION_ERROR = "skills_action_error"

    fun section(section: SkillsPageSection): String = "skills_section_${section.name}"

    fun jump(section: SkillsPageSection): String = "skills_jump_${section.name}"

    fun skill(name: String): String = "skills_skill_$name"

    fun skillToggle(name: String): String = "skills_skill_toggle_$name"

    fun tool(id: String): String = "skills_tool_$id"
}

/** The page's own view state: the search and the skills filter. */
@Immutable
internal data class SkillsPageQuery(val query: String = "", val assignedOnly: Boolean = false)

internal class SkillsPageControls(
    val onQueryChange: (String) -> Unit,
    val onAssignedOnlyChange: (Boolean) -> Unit,
    val onSelectSkill: (String?) -> Unit,
    val onJumpTo: (SkillsPageSection) -> Unit,
    /** Opens a tool's read-only detail (and tells the host, through [SkillsPageActions.onToolClick]). */
    val onSelectTool: (Tool) -> Unit = {},
)

internal class SkillsPageScope(
    val state: SkillsPageState,
    val actions: SkillsPageActions,
    val options: SkillsPageOptions,
    val wide: Boolean,
    val filter: SkillsPageQuery,
    val rows: List<SkillsPageRow>,
    val listState: LazyListState,
    val controls: SkillsPageControls,
    val selectedSkill: Skill?,
) {
    /** A reload of skills or tools already listed (a first load shows its own info row instead). */
    val refreshing: Boolean
        get() = (state.skillsLoading && state.skills.isNotEmpty()) || (state.tools.isLoading && state.tools.tools.isNotEmpty())

    /** One refresh reloads both sections. */
    fun refresh() {
        actions.onRefreshSkills()
        actions.onRefreshTools()
    }
}

private fun CoroutineScope.jumpTo(listState: LazyListState, rows: List<SkillsPageRow>, section: SkillsPageSection) {
    val index = SkillsPageRows.indexOf(rows, section)
    if (index >= 0) launch { listState.animateScrollToItem(index) }
}

/** Opens the page on [SkillsPageOptions.initialSection] once; later loads keep the anchor by key. */
@Composable
private fun InitialSectionJump(page: SkillsPageScope) {
    val target = page.options.initialSection
    var jumped by rememberSaveable { mutableStateOf(target == SkillsPageSection.Skills) }
    LaunchedEffect(target, jumped) {
        if (jumped) return@LaunchedEffect
        val index = SkillsPageRows.indexOf(page.rows, target)
        if (index >= 0) page.listState.scrollToItem(index)
        jumped = true
    }
}

@Composable
private fun WideSkillsLayout(page: SkillsPageScope) {
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            SkillsHeader(page)
            SkillsBody(page, WideListPadding)
        }
        page.selectedSkill?.let { skill ->
            VerticalDivider()
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.width(LettaDimens.Pane.sidePanelWidth).fillMaxHeight(),
            ) {
                SkillDetail(skill, page)
            }
        }
    }
}

@Composable
private fun CompactSkillsLayout(page: SkillsPageScope) {
    Column(Modifier.fillMaxSize()) {
        SkillsHeader(page)
        SkillsBody(page, CompactListPadding)
    }
    page.selectedSkill?.let { skill -> SkillDetailSheet(skill, page) }
}

/** Skills then tools, pulled down on touch to reload both. */
@Composable
private fun SkillsBody(page: SkillsPageScope, contentPadding: PaddingValues) {
    LettaRefreshable(refreshing = page.refreshing, onRefresh = page::refresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = page.listState,
            modifier = Modifier.fillMaxSize().testTag(SkillsPageTags.LIST),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
            contentPadding = contentPadding,
        ) {
            items(page.rows, key = { it.key }) { row -> SkillsPageRowContent(row, page) }
        }
    }
}

private val WideListPadding = PaddingValues(
    start = LettaDimens.Space.xxl,
    end = LettaDimens.Space.xxl,
    top = LettaDimens.Space.sm,
    bottom = LettaDimens.Space.xxl,
)
private val CompactListPadding = PaddingValues(
    start = LettaDimens.Space.lg,
    end = LettaDimens.Space.lg,
    top = LettaDimens.Space.sm,
    bottom = LettaDimens.Space.xxl,
)
