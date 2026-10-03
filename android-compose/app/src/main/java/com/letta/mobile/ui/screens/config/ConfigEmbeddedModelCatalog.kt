package com.letta.mobile.ui.screens.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ca.oculair.meridian.R
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelCatalogItem
import com.letta.mobile.runtime.local.modelcatalog.EmbeddedModelDownloadState
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.util.FormatHelpers
import kotlin.math.roundToInt

// The local model settings' embedded catalog: one card per model, with its download controls.

/** One catalog row's facts: the model, whether it is the selected file, and whether a token is set. */
private class EmbeddedModelRowState(
    val item: EmbeddedModelCatalogItem,
    val selected: Boolean,
    val hasHuggingFaceToken: Boolean,
)

@Composable
internal fun EmbeddedModelCatalogSection(state: ConfigUiState, callbacks: EmbeddedModelCallbacks) {
    val items = state.embeddedModelCatalog
    val hasHuggingFaceToken = state.huggingFaceToken.isNotBlank()
    Column(verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
        Text(
            text = stringResource(R.string.screen_config_embedded_model_catalog),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (items.isEmpty()) {
            Text(
                text = stringResource(R.string.screen_config_embedded_model_catalog_empty),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        items.forEach { item ->
            EmbeddedModelCatalogRow(
                row = EmbeddedModelRowState(
                    item = item,
                    selected = (item.state as? EmbeddedModelDownloadState.Downloaded)?.localPath == state.localModelPath,
                    hasHuggingFaceToken = hasHuggingFaceToken,
                ),
                callbacks = callbacks,
            )
        }
    }
}

@Composable
private fun EmbeddedModelCatalogRow(row: EmbeddedModelRowState, callbacks: EmbeddedModelCallbacks) {
    val entry = row.item.entry
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(LettaDimens.Space.md),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        ) {
            Text(entry.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = stringResource(
                    R.string.screen_config_embedded_model_size,
                    FormatHelpers.formatByteSize(entry.sizeInBytes),
                    FormatHelpers.formatByteSize(entry.estimatedPeakMemoryInBytes),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            if (entry.requiresAuth && !row.hasHuggingFaceToken) {
                HuggingFaceTokenRequiredNotice()
            }
            if (!entry.isSupported) {
                AssistChip(
                    enabled = false,
                    onClick = {},
                    label = { Text(entry.unsupportedReason ?: stringResource(R.string.screen_config_embedded_model_unsupported)) },
                )
            } else {
                EmbeddedModelDownloadControls(row = row, callbacks = callbacks)
            }
        }
    }
}

@Composable
private fun HuggingFaceTokenRequiredNotice() {
    AssistChip(
        enabled = false,
        onClick = {},
        label = { Text(stringResource(R.string.screen_config_embedded_model_requires_hf_token_badge)) },
    )
    Text(
        text = stringResource(R.string.screen_config_embedded_model_requires_hf_token_message),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** What the row offers for its download state: download (or retry), cancel, or select. */
@Composable
private fun EmbeddedModelDownloadControls(row: EmbeddedModelRowState, callbacks: EmbeddedModelCallbacks) {
    val item = row.item
    when (val state = item.state) {
        EmbeddedModelDownloadState.Idle,
        EmbeddedModelDownloadState.Cancelled,
        is EmbeddedModelDownloadState.Failed -> DownloadableModelControls(
            row = row,
            onDownload = { callbacks.onDownloadEmbeddedModel(item) },
        )
        is EmbeddedModelDownloadState.Downloading -> DownloadingModelControls(
            state = state,
            onCancel = { callbacks.onCancelEmbeddedModelDownload(item) },
        )
        is EmbeddedModelDownloadState.Downloaded -> DownloadedModelButton(
            row = row,
            onSelect = { callbacks.onSelectEmbeddedModel(item) },
        )
    }
}

@Composable
private fun DownloadableModelControls(row: EmbeddedModelRowState, onDownload: () -> Unit) {
    val state = row.item.state
    if (state is EmbeddedModelDownloadState.Failed) {
        Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    val downloadEnabled = !row.item.entry.requiresAuth || row.hasHuggingFaceToken
    OutlinedButton(
        onClick = onDownload,
        modifier = Modifier.fillMaxWidth(),
        enabled = downloadEnabled,
    ) {
        Text(
            stringResource(
                if (downloadEnabled) {
                    R.string.screen_config_embedded_model_download
                } else {
                    R.string.screen_config_embedded_model_add_hf_token
                }
            )
        )
    }
}

@Composable
private fun DownloadingModelControls(state: EmbeddedModelDownloadState.Downloading, onCancel: () -> Unit) {
    val progress = embeddedModelDownloadProgress(state.bytesDownloaded, state.totalBytes)
    if (progress != null) {
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
    Text(
        text = embeddedModelDownloadProgressLabel(state.bytesDownloaded, state.totalBytes),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.screen_config_embedded_model_cancel))
    }
}

@Composable
private fun DownloadedModelButton(row: EmbeddedModelRowState, onSelect: () -> Unit) {
    val selected = row.selected
    FilledTonalButton(onClick = onSelect, modifier = Modifier.fillMaxWidth(), enabled = !selected) {
        Text(
            stringResource(
                if (selected) R.string.screen_config_embedded_model_downloaded
                else R.string.screen_config_embedded_model_select,
            )
        )
    }
}

fun embeddedModelDownloadProgress(bytesDownloaded: Long, totalBytes: Long?): Float? {
    val total = totalBytes?.takeIf { it > 0L } ?: return null
    return (bytesDownloaded.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
}

fun embeddedModelDownloadProgressLabel(bytesDownloaded: Long, totalBytes: Long?): String {
    val downloaded = FormatHelpers.formatByteSize(bytesDownloaded.coerceAtLeast(0L))
    val total = totalBytes?.takeIf { it > 0L } ?: return downloaded
    val percent = ((bytesDownloaded.toDouble() / total.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
    return "$downloaded / ${FormatHelpers.formatByteSize(total)} · $percent%"
}
