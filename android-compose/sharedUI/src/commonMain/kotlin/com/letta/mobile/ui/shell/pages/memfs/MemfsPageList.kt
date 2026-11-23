package com.letta.mobile.ui.shell.pages.memfs

import com.letta.mobile.ui.theme.lettaPageGutter
import com.letta.mobile.ui.components.LettaRefreshAction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.letta.mobile.data.memory.memfs.MemfsEmptyReason
import com.letta.mobile.data.memory.memfs.MemfsFile
import com.letta.mobile.data.memory.memfs.MemfsFileKind
import com.letta.mobile.data.memory.memfs.MemfsLoad
import com.letta.mobile.data.memory.memfs.MemfsTab
import com.letta.mobile.ui.components.row.LettaListRowSpec
import com.letta.mobile.ui.components.foundation.LettaChipTab
import com.letta.mobile.ui.components.foundation.LettaEmptyContent
import com.letta.mobile.ui.components.foundation.LettaEmptyState
import com.letta.mobile.ui.components.foundation.LettaInlineNotice
import com.letta.mobile.ui.components.foundation.LettaNotice
import com.letta.mobile.ui.components.foundation.LettaNoticeAction
import com.letta.mobile.ui.components.foundation.LettaSearchField
import com.letta.mobile.ui.components.foundation.LettaSearchOptions
import com.letta.mobile.ui.theme.tokens.LettaPanelTone
import com.letta.mobile.ui.components.foundation.LettaChipTabRow
import com.letta.mobile.ui.components.LettaPageHeader
import com.letta.mobile.ui.components.list.RosterList
import com.letta.mobile.ui.components.list.RosterListRow
import com.letta.mobile.ui.components.list.RosterListTag
import com.letta.mobile.ui.components.list.RosterOptions
import com.letta.mobile.ui.components.list.RosterSection
import com.letta.mobile.ui.components.list.RosterSelection
import com.letta.mobile.ui.components.list.RosterSpec
import com.letta.mobile.ui.components.foundation.LettaBusyBar
import com.letta.mobile.ui.theme.LettaDimens

/**
 * The shared page header: the title with the refresh button at its end (pointer hosts only; touch
 * pulls the list), then the host's controls (the Memory destination's Blocks | Files switch). On a
 * phone the list's own controls follow in the same header; a wide page shows them above its list.
 */
@Composable
internal fun MemfsHeader(page: MemfsPageScope) {
    LettaPageHeader(
        title = page.options.title.takeIf { page.options.showTitle },
        refresh = {
            LettaRefreshAction(page.state.isLoading, page.actions::refresh, Modifier.testTag(MemfsPageTags.REFRESH))
        },
        controls = page.options.headerControls,
    ) {
        if (!page.wide) MemfsListControls(page)
    }
}

/** A wide page's list controls, above the docked list and on the page's gutter. */
@Composable
internal fun MemfsDockedListControls(page: MemfsPageScope) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = lettaPageGutter(wide = true))
            .padding(bottom = LettaDimens.Space.sm),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) { MemfsListControls(page) }
}

/** The filter field first, then the Current | History switch and the load's progress bar. */
@Composable
private fun MemfsListControls(page: MemfsPageScope) {
    if (page.state.tab == MemfsTab.Files) {
        LettaSearchField(
            query = page.state.query,
            onQueryChange = page.actions::updateQuery,
            modifier = Modifier.fillMaxWidth().testTag(MemfsPageTags.SEARCH),
            options = LettaSearchOptions(placeholder = SEARCH_PLACEHOLDER),
        )
    }
    MemfsTabs(page)
    if (page.state.isLoading || page.state.history.loading) {
        LettaBusyBar()
    }
}

/** Files' sub-view: the current tree or its history; "Current", since the mode switch already says Files. */
@Composable
private fun MemfsTabs(page: MemfsPageScope) {
    LettaChipTabRow(Modifier.fillMaxWidth()) {
        MemfsTab.entries.forEach { tab ->
            LettaChipTab(
                text = if (tab == MemfsTab.Files) CURRENT_TAB else HISTORY_TAB,
                active = page.state.tab == tab,
                onClick = { page.actions.selectTab(tab) },
                modifier = Modifier.testTag(MemfsPageTags.tab(tab)),
            )
        }
    }
}

@Composable
internal fun MemfsFileList(page: MemfsPageScope) {
    val state = page.state
    val load = state.load
    val empty = state.emptyReason
    when {
        load is MemfsLoad.Failed -> MemfsErrorState(load.message, page)
        empty != null -> MemfsEmptyState(empty, page)
        // letta-mobile-ckmub.3.25: the files are the shared roster (System, then External); the open
        // file reads selected, and the keyboard moves through them.
        else -> RosterList(
            spec = RosterSpec(
                sections = listOfNotNull(
                    fileSection(SYSTEM_SECTION, state.systemFiles),
                    fileSection(EXTERNAL_SECTION, state.externalFiles),
                ),
                key = MemfsFile::path,
                selection = RosterSelection(isSelected = { it.path == state.editor?.path }, onSelect = { page.actions.openFile(it.path) }),
                options = RosterOptions(testTag = RosterListTag(MemfsPageTags.LIST), contentPadding = ListPadding),
            ),
            modifier = Modifier.fillMaxSize(),
        ) { file, row ->
            val rowModifier = if (page.options.touch) Modifier.minimumInteractiveComponentSize() else Modifier
            RosterListRow(
                spec = LettaListRowSpec(
                    // The section already says "System", so its files drop the folder.
                    title = if (file.isSystem) file.path.removePrefix(SYSTEM_PREFIX) else file.path,
                    icon = if (file.kind == MemfsFileKind.Image) Icons.Outlined.Image else Icons.Outlined.Description,
                    trailing = formatSize(file.sizeBytes),
                ),
                row = row,
                modifier = rowModifier.testTag(MemfsPageTags.file(file.path)),
            )
        }
    }
}

private fun fileSection(title: String, files: List<MemfsFile>): RosterSection<MemfsFile>? =
    files.takeIf { it.isNotEmpty() }?.let { RosterSection(key = title, items = it, title = title) }

@Composable
private fun MemfsEmptyState(reason: MemfsEmptyReason, page: MemfsPageScope) {
    val canEnable = reason == MemfsEmptyReason.MemfsDisabled && !page.options.readOnly
    // Scrolls (with nothing to scroll) so a pull on the message still reaches LettaRefreshable.
    LettaEmptyState(
        content = LettaEmptyContent(reason.message),
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag(MemfsPageTags.EMPTY),
        action = if (canEnable) ({ MemfsEnableAction(page) }) else null,
    )
}

@Composable
private fun MemfsEnableAction(page: MemfsPageScope) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
        Button(
            onClick = page.actions::enableMemfs,
            enabled = !page.state.enabling,
            modifier = Modifier.testTag(MemfsPageTags.ENABLE),
        ) { Text(if (page.state.enabling) ENABLING_LABEL else ENABLE_LABEL) }
        page.state.enableError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun MemfsErrorState(message: String, page: MemfsPageScope) {
    // Scrolls (with nothing to scroll) so a pull on the message still reaches LettaRefreshable.
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(LettaDimens.Space.xl)) {
        LettaInlineNotice(
            notice = LettaNotice(message, tone = LettaPanelTone.Error),
            modifier = Modifier.testTag(MemfsPageTags.ERROR),
            action = LettaNoticeAction(RETRY_LABEL, page.actions::refresh),
        )
    }
}

/** The placeholder in the detail pane before anything is open. */
@Composable
internal fun MemfsDetailHint(message: String) {
    LettaEmptyState(LettaEmptyContent(message), Modifier.fillMaxSize())
}

/** A compact byte count: `512 B`, `3.4 KB`, `1.2 MB`. */
internal fun formatSize(bytes: Long): String = when {
    bytes < KILOBYTE -> "$bytes B"
    bytes < KILOBYTE * KILOBYTE -> "${oneDecimal(bytes / KILOBYTE.toDouble())} KB"
    else -> "${oneDecimal(bytes / (KILOBYTE * KILOBYTE).toDouble())} MB"
}

private fun oneDecimal(value: Double): String {
    val tenths = kotlin.math.round(value * TENTHS).toLong()
    return "${tenths / TENTHS.toLong()}.${tenths % TENTHS.toLong()}"
}

private const val KILOBYTE = 1024L
private const val TENTHS = 10.0
private const val SYSTEM_PREFIX = "system/"
private const val SEARCH_PLACEHOLDER = "Filter memory files"
private const val CURRENT_TAB = "Current"
private const val HISTORY_TAB = "History"
private const val SYSTEM_SECTION = "System"
private const val EXTERNAL_SECTION = "External"
private const val ENABLE_LABEL = "Enable memory files"
private const val ENABLING_LABEL = "Enabling…"
private const val RETRY_LABEL = "Try again"
private val ListPadding = PaddingValues(
    start = LettaDimens.Space.md,
    end = LettaDimens.Space.md,
    bottom = LettaDimens.Space.xl,
)
