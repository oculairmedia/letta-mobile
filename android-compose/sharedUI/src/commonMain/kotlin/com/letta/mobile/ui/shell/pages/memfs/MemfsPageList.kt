package com.letta.mobile.ui.shell.pages.memfs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import com.letta.mobile.data.memory.memfs.MemfsEmptyReason
import com.letta.mobile.data.memory.memfs.MemfsFile
import com.letta.mobile.data.memory.memfs.MemfsFileKind
import com.letta.mobile.data.memory.memfs.MemfsLoad
import com.letta.mobile.data.memory.memfs.MemfsTab
import com.letta.mobile.ui.components.LettaListRow
import com.letta.mobile.ui.components.LettaListRowSpec
import com.letta.mobile.ui.components.LettaSectionLabel
import com.letta.mobile.ui.theme.LettaDimens

@Composable
internal fun MemfsHeader(page: MemfsPageScope) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.md),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (page.options.showTitle) Text(PAGE_TITLE, style = MaterialTheme.typography.headlineSmall)
            }
            IconButton(onClick = page.actions::refresh, modifier = Modifier.testTag(MemfsPageTags.REFRESH)) {
                Icon(Icons.Outlined.Refresh, contentDescription = REFRESH_LABEL)
            }
        }
        MemfsTabs(page)
        if (page.state.tab == MemfsTab.Files) {
            OutlinedTextField(
                value = page.state.query,
                onValueChange = page.actions::updateQuery,
                placeholder = { Text(SEARCH_PLACEHOLDER) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().testTag(MemfsPageTags.SEARCH),
            )
        }
        if (page.state.isLoading || page.state.history.loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun MemfsTabs(page: MemfsPageScope) {
    val tabs = MemfsTab.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        tabs.forEachIndexed { index, tab ->
            SegmentedButton(
                selected = page.state.tab == tab,
                onClick = { page.actions.selectTab(tab) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = tabs.size),
                modifier = Modifier.testTag(MemfsPageTags.tab(tab)),
            ) {
                Text(if (tab == MemfsTab.Files) FILES_TAB else HISTORY_TAB)
            }
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
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize().testTag(MemfsPageTags.LIST),
            contentPadding = ListPadding,
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.hair),
        ) {
            fileSection(SYSTEM_SECTION, state.systemFiles, page)
            fileSection(EXTERNAL_SECTION, state.externalFiles, page)
        }
    }
}

private fun LazyListScope.fileSection(title: String, files: List<MemfsFile>, page: MemfsPageScope) {
    if (files.isEmpty()) return
    item(key = "section_$title") { LettaSectionLabel(title) }
    items(files, key = { it.path }) { file ->
        val rowModifier = if (page.options.touch) Modifier.minimumInteractiveComponentSize() else Modifier
        LettaListRow(
            spec = LettaListRowSpec(
                // The section already says "System", so its files drop the folder.
                title = if (file.isSystem) file.path.removePrefix(SYSTEM_PREFIX) else file.path,
                icon = if (file.kind == MemfsFileKind.Image) Icons.Outlined.Image else Icons.Outlined.Description,
                trailing = formatSize(file.sizeBytes),
                selected = page.state.editor?.path == file.path,
            ),
            onClick = { page.actions.openFile(file.path) },
            modifier = rowModifier.testTag(MemfsPageTags.file(file.path)),
        )
    }
}

@Composable
private fun MemfsEmptyState(reason: MemfsEmptyReason, page: MemfsPageScope) {
    MemfsMessage(reason.message, Modifier.testTag(MemfsPageTags.EMPTY)) {
        if (reason == MemfsEmptyReason.MemfsDisabled && !page.options.readOnly) {
            Button(
                onClick = page.actions::enableMemfs,
                enabled = !page.state.enabling,
                modifier = Modifier.testTag(MemfsPageTags.ENABLE),
            ) { Text(if (page.state.enabling) ENABLING_LABEL else ENABLE_LABEL) }
            page.state.enableError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun MemfsErrorState(message: String, page: MemfsPageScope) {
    MemfsMessage(message, Modifier.testTag(MemfsPageTags.ERROR), color = MaterialTheme.colorScheme.error) {
        OutlinedButton(onClick = page.actions::refresh) { Text(RETRY_LABEL) }
    }
}

@Composable
private fun MemfsMessage(
    message: String,
    modifier: Modifier,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    actions: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(LettaDimens.Space.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = color, textAlign = TextAlign.Center)
        actions()
    }
}

/** The placeholder in the detail pane before anything is open. */
@Composable
internal fun MemfsDetailHint(message: String) {
    Box(Modifier.fillMaxSize().padding(LettaDimens.Space.xl), contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
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
private const val PAGE_TITLE = "Memory files"
private const val REFRESH_LABEL = "Refresh"
private const val SEARCH_PLACEHOLDER = "Filter memory files"
private const val FILES_TAB = "Files"
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
