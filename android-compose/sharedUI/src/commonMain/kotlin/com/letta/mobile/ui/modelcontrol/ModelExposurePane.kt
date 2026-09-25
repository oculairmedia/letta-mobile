package com.letta.mobile.ui.modelcontrol

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.data.repository.modelcontrol.CatalogModel
import com.letta.mobile.data.repository.modelcontrol.ModelExposureState
import com.letta.mobile.ui.components.LettaEmptyHint
import com.letta.mobile.ui.components.LettaSectionLabel
import com.letta.mobile.ui.theme.LettaDimens

private const val HIDDEN_ALPHA = 0.5f

/**
 * Which of the host's models the app model pickers show (letta-mobile-w4q4p).
 * Exposed models first; hidden ones greyed under "Hidden". Shared by the
 * Android Model Browser and the desktop Models pane.
 */
@Composable
fun ModelExposurePane(
    state: ModelExposureState,
    onQueryChange: (String) -> Unit,
    onExposedChange: (handle: String, exposed: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().testTag(ModelExposureTags.PANE)) {
        if (state.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        ModelControlNotice(error = state.error, message = null)
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            label = { Text("Filter models") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = LettaDimens.Space.lg),
        )
        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = LettaDimens.Space.lg)) {
            item { LettaSectionLabel("Shown in pickers (${state.exposed.size})") }
            items(state.exposed, key = { "e-${it.handle}" }) { ExposureRow(it, onExposedChange) }
            item { LettaSectionLabel("Hidden (${state.hidden.size})") }
            if (state.hidden.isEmpty()) item { LettaEmptyHint("No hidden models") }
            items(state.hidden, key = { "h-${it.handle}" }) { ExposureRow(it, onExposedChange) }
        }
    }
}

@Composable
private fun ExposureRow(model: CatalogModel, onExposedChange: (String, Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (model.exposed) 1f else HIDDEN_ALPHA)
            .padding(vertical = LettaDimens.Space.xs)
            .testTag("${ModelExposureTags.ROW_PREFIX}${model.handle}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(model.model.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                model.handle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(checked = model.exposed, onCheckedChange = { onExposedChange(model.handle, it) })
    }
}

/**
 * Reasoning-effort chooser for a model that advertises variants. "Default"
 * restores the provider default (`reasoning_effort: null`).
 */
@Composable
fun ReasoningEffortChips(
    efforts: List<String>,
    onSelect: (effort: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (efforts.isEmpty()) return
    FlowRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
        AssistChip(onClick = { onSelect(null) }, label = { Text("Default") })
        efforts.forEach { effort ->
            AssistChip(
                onClick = { onSelect(effort) },
                label = { Text(effort) },
                modifier = Modifier.testTag("reasoning_effort_$effort"),
            )
        }
    }
}

object ModelExposureTags {
    const val PANE = "model_exposure_pane"
    const val ROW_PREFIX = "model_exposure_row_"
}
