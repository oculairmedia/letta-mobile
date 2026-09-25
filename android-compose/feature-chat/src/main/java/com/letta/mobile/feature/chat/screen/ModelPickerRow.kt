package com.letta.mobile.feature.chat.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.feature.chat.R
import com.letta.mobile.ui.icons.LettaIconSizing
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.modelcontrol.ReasoningEffortChips
import com.letta.mobile.ui.theme.LettaDimens

/**
 * letta-mobile-w4q4p: reasoning variants the host advertises per handle, and
 * the callback that switches model + effort together (effort null = provider
 * default). The default renders no chips.
 */
internal data class ModelPickerReasoning(
    val effortsFor: (String) -> List<String> = { emptyList() },
    val onEffortSelected: (handle: String, effort: String?) -> Unit = { _, _ -> },
)

/** Supplied by the chat scaffold; pickers opened elsewhere show no effort chips. */
internal val LocalModelPickerReasoning = staticCompositionLocalOf { ModelPickerReasoning() }

internal data class ModelPickerRowSpec(
    val handle: String,
    val isActive: Boolean,
    val enabled: Boolean,
    val efforts: List<String> = emptyList(),
    val subtitle: String = "",
)

/** One model in the picker: name, tier, subtitle, reasoning chips, and the active check. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ModelPickerRow(
    model: LlmModel,
    spec: ModelPickerRowSpec,
    onSelect: () -> Unit,
    onEffortSelected: (String?) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("model_row_${spec.handle}")
            .combinedClickable(enabled = spec.enabled && !spec.isActive, onClick = onSelect),
        colors = CardDefaults.cardColors(
            containerColor = if (spec.isActive) MaterialTheme.colorScheme.primaryContainer else CardDefaults.cardColors().containerColor,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(LettaDimens.Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                ModelTitleLine(model)
                Spacer(modifier = Modifier.height(LettaDimens.Space.hair))
                Text(
                    text = spec.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ReasoningEffortChips(efforts = spec.efforts, onSelect = onEffortSelected)
            }
            if (spec.isActive) ActiveModelCheck()
        }
    }
}

@Composable
private fun ModelTitleLine(model: LlmModel) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        Text(
            text = model.displayName,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        // Tier badge — only when the API provides it.
        model.tier?.takeIf { it.isNotBlank() }?.let { tier ->
            AssistChip(
                onClick = {},
                label = { Text(tier.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.height(LettaDimens.Space.xl),
            )
        }
    }
}

@Composable
private fun ActiveModelCheck() {
    Icon(
        LettaIcons.CheckCircle,
        contentDescription = stringResource(R.string.screen_agents_current_indicator),
        modifier = Modifier.padding(start = LettaDimens.Space.sm).size(LettaIconSizing.Toolbar),
        tint = MaterialTheme.colorScheme.primary,
    )
}
