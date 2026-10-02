package com.letta.mobile.ui.screens.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import ca.oculair.meridian.R
import com.letta.mobile.runtime.local.EmbeddedLettaCodeRuntimeStatus
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.preview.LettaPreviewFrame
import com.letta.mobile.ui.theme.LettaDimens

// The server card's on-device runtime status item, shown while the mode is local.

/** The embedded runtime's version and whether it may execute, as a header and two chips. */
@Composable
internal fun EmbeddedRuntimeStatusItem(
    status: EmbeddedLettaCodeRuntimeStatus,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        EmbeddedRuntimeStatusHeader(status)
        EmbeddedRuntimeStatusChips(status)
    }
}

@Composable
private fun EmbeddedRuntimeStatusHeader(status: EmbeddedLettaCodeRuntimeStatus) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = LettaIcons.Psychology,
            contentDescription = null,
            modifier = Modifier.padding(end = LettaDimens.Space.sm),
        )
        Column(verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.hair)) {
            Text(
                text = stringResource(R.string.screen_config_embedded_runtime_title),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(
                    if (status.runnable) {
                        R.string.screen_config_embedded_runtime_enabled_placeholder
                    } else {
                        R.string.screen_config_embedded_runtime_disabled_placeholder
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (status.runnable) {
                Text(
                    text = stringResource(R.string.screen_config_embedded_runtime_notifications_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EmbeddedRuntimeStatusChips(status: EmbeddedLettaCodeRuntimeStatus) {
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        AssistChip(
            onClick = {},
            enabled = false,
            label = {
                Text(stringResource(R.string.screen_config_embedded_runtime_version, status.version.ifBlank { "disabled" }))
            },
        )
        AssistChip(
            onClick = {},
            enabled = false,
            label = {
                Text(
                    stringResource(
                        if (status.runnable) {
                            R.string.screen_config_embedded_runtime_execution_enabled
                        } else {
                            R.string.screen_config_embedded_runtime_execution_disabled
                        }
                    )
                )
            },
        )
    }
}

// region Previews

private val previewEmbeddedRuntimeStatus = EmbeddedLettaCodeRuntimeStatus(
    nativeEnabled = true,
    assetsEnabled = true,
    version = "0.1.0",
    integrity = "ok",
)

@PreviewLightDark
@Composable
private fun EmbeddedRuntimeStatusItemPreview() {
    LettaPreviewFrame {
        EmbeddedRuntimeStatusItem(status = previewEmbeddedRuntimeStatus)
    }
}

// endregion
