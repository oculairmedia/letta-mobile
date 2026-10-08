package com.letta.mobile.ui.shell.pages.memfs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.data.diff.DiffLine
import com.letta.mobile.data.diff.DiffLineKind
import com.letta.mobile.data.memory.memfs.MemfsCommit
import com.letta.mobile.data.memory.memfs.MemfsFileChange
import com.letta.mobile.data.memory.memfs.MemfsFileDiff
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.customColors

/** The memory repository's commits, for one file or all of them. */
@Composable
internal fun MemfsCommitList(page: MemfsPageScope) {
    val history = page.state.history
    Column(Modifier.fillMaxSize().testTag(MemfsPageTags.HISTORY)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = LettaDimens.Space.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        ) {
            Text(
                history.path ?: ALL_FILES,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (history.path != null) {
                AssistChip(
                    onClick = { page.actions.showHistory(null) },
                    label = { Text(ALL_FILES) },
                    modifier = Modifier.testTag(MemfsPageTags.HISTORY_ALL),
                )
            }
        }
        history.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(LettaDimens.Space.lg)) }
        if (!history.loading && history.error == null && history.commits.isEmpty()) {
            MemfsDetailHint(NO_COMMITS)
        }
        LazyColumn(contentPadding = HistoryPadding, verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.hair)) {
            items(history.commits, key = { it.sha }) { commit ->
                CommitRow(commit, selected = commit.sha == history.selectedSha) { page.actions.selectCommit(commit.sha) }
            }
        }
    }
}

@Composable
private fun CommitRow(commit: MemfsCommit, selected: Boolean, onClick: () -> Unit) {
    val background = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(background)
            .clickable(onClick = onClick)
            .padding(LettaDimens.Space.sm)
            .testTag(MemfsPageTags.commit(commit.sha)),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.hair),
    ) {
        Text(commit.message.ifBlank { NO_MESSAGE }, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(commit.shortSha, commit.author, commit.timestamp.take(TIMESTAMP_MINUTES)).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** What the selected commit changed, file by file. */
@Composable
internal fun MemfsDiffPane(page: MemfsPageScope) {
    val history = page.state.history
    Column(Modifier.fillMaxSize().testTag(MemfsPageTags.DIFF)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = LettaDimens.Space.sm, vertical = LettaDimens.Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = page.actions::clearCommit, modifier = Modifier.testTag(MemfsPageTags.BACK)) {
                Icon(if (page.wide) Icons.Outlined.Close else Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = CLOSE_COMMIT)
            }
            Text(
                history.selectedCommit?.message ?: history.selectedSha.orEmpty(),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (history.diffLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        history.diffError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(LettaDimens.Space.lg)) }
        if (!history.diffLoading && history.diffError == null && history.diff.isEmpty()) MemfsDetailHint(EMPTY_COMMIT)
        LazyColumn(contentPadding = HistoryPadding, verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md)) {
            items(history.diff, key = { it.path }) { file -> FileDiff(file) }
        }
    }
}

@Composable
private fun FileDiff(file: MemfsFileDiff) {
    Column(Modifier.fillMaxWidth().testTag(MemfsPageTags.diffFile(file.path))) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
            Text(file.path, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(changeLabel(file), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = LettaDimens.Space.xs)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .horizontalScroll(rememberScrollState()),
        ) {
            file.lines.forEach { line -> DiffRow(line) }
        }
    }
}

@Composable
private fun DiffRow(line: DiffLine) {
    val success = MaterialTheme.customColors.successColor.takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.primary
    val (marker, color) = when (line.kind) {
        DiffLineKind.Added -> "+" to success
        DiffLineKind.Removed -> "-" to MaterialTheme.colorScheme.error
        DiffLineKind.Hunk -> "" to MaterialTheme.colorScheme.onSurfaceVariant
        else -> " " to MaterialTheme.colorScheme.onSurface
    }
    Row(Modifier.padding(horizontal = LettaDimens.Space.sm)) {
        Text(
            (line.newLine ?: line.oldLine)?.toString().orEmpty(),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(LettaDimens.Control.iconButtonLg),
        )
        Text(
            marker + line.text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = color,
            softWrap = false,
        )
    }
}

private fun changeLabel(file: MemfsFileDiff): String = when (file.change) {
    MemfsFileChange.Added -> "added · +${file.added}"
    MemfsFileChange.Deleted -> "deleted · -${file.removed}"
    MemfsFileChange.Binary -> "binary"
    MemfsFileChange.Modified -> "+${file.added} -${file.removed}"
}

private const val ALL_FILES = "All files"
private const val NO_COMMITS = "No commits yet."
private const val NO_MESSAGE = "(no message)"
private const val CLOSE_COMMIT = "Close commit"
private const val EMPTY_COMMIT = "This commit changed no files."
private const val TIMESTAMP_MINUTES = 16
private val HistoryPadding = PaddingValues(
    start = LettaDimens.Space.md,
    end = LettaDimens.Space.md,
    top = LettaDimens.Space.sm,
    bottom = LettaDimens.Space.xl,
)
