package com.letta.mobile.ui.modelcontrol

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.data.repository.modelcontrol.CatalogRow
import com.letta.mobile.data.repository.modelcontrol.ExposureChange
import com.letta.mobile.data.repository.modelcontrol.ProviderManagementController
import com.letta.mobile.data.repository.modelcontrol.ProviderManagementState
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens

/** Callbacks of [ModelsEditContent]. */
data class ModelsEditActions(
    val onClose: () -> Unit,
    val onQueryChange: (String) -> Unit,
    val onExposedChange: (ExposureChange) -> Unit,
    /** Opens the Providers settings page; null hides "Add provider…". */
    val onAddProvider: (() -> Unit)?,
) {
    companion object {
        fun bind(controller: ProviderManagementController, onClose: () -> Unit, onAddProvider: (() -> Unit)?) = ModelsEditActions(
            onClose = onClose,
            onQueryChange = controller::setQuery,
            onExposedChange = controller::setExposed,
            onAddProvider = onAddProvider,
        )
    }
}

object ModelsEditTags {
    const val SHEET = "models_edit"
    const val CLOSE = "models_edit_close"
    const val SEARCH = "models_edit_search"
    const val SECTION_PREFIX = "models_edit_section_"
    const val ROW_PREFIX = "models_edit_row_"
    const val ADD_PROVIDER = "models_edit_add_provider"
}

/** Hidden models dim so the shown ones read first. */
private const val HIDDEN_ALPHA = 0.6f

/**
 * "Models" (letta-mobile-w4q4p.6.1): every model the host serves, under its
 * provider, with a switch that shows or hides it in the picker
 * (`model.exposure.set`). Opened from the picker's "Edit Models…"; a compact
 * card on desktop, a bottom sheet on Android ([ModelControlModal]).
 */
@Composable
fun ColumnScope.ModelsEditContent(
    state: ProviderManagementState,
    actions: ModelsEditActions,
    modifier: Modifier = Modifier,
    autoFocusSearch: Boolean = false,
) {
    Column(modifier = modifier.weight(1f, fill = false).testTag(ModelsEditTags.SHEET)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = LettaDimens.Space.lg, end = LettaDimens.Space.xs, top = LettaDimens.Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = ModelControlStrings.MODELS_TITLE,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            IconButton(onClick = actions.onClose, modifier = Modifier.testTag(ModelsEditTags.CLOSE)) {
                Icon(LettaIcons.Close, contentDescription = ModelControlStrings.CLOSE)
            }
        }
        ModelSearchField(
            query = state.query,
            onQueryChange = actions.onQueryChange,
            testTag = ModelsEditTags.SEARCH,
            autoFocus = autoFocusSearch,
        )
        if (state.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        ModelControlNotice(error = state.error, message = null)
        val sections = state.visible.filter { it.rows.isNotEmpty() }
        LazyColumn(modifier = Modifier.weight(1f, fill = false).fillMaxWidth()) {
            if (sections.isEmpty() && !state.loading) {
                item("empty") {
                    Text(
                        text = if (state.searching) ModelControlStrings.noMatch(state.query.trim()) else ModelControlStrings.NO_PROVIDER_MODELS,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(LettaDimens.Space.xl),
                    )
                }
            }
            sections.forEach { visible ->
                val section = visible.section
                item("section-${section.key}") {
                    ProviderCapsTitle(
                        title = section.displayName,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { heading() }
                            .padding(start = LettaDimens.Space.lg, end = LettaDimens.Space.lg, top = LettaDimens.Space.md, bottom = LettaDimens.Space.xs)
                            .testTag("${ModelsEditTags.SECTION_PREFIX}${section.key}"),
                    )
                }
                itemsIndexed(visible.rows, key = { index, row -> "row-${section.key}-$index-${row.handle.value}" }) { _, row ->
                    ExposureRow(row, actions.onExposedChange)
                }
            }
        }
        actions.onAddProvider?.let { onAdd ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            TextButton(
                onClick = onAdd,
                modifier = Modifier.padding(horizontal = LettaDimens.Space.sm, vertical = LettaDimens.Space.xs).testTag(ModelsEditTags.ADD_PROVIDER),
            ) {
                Icon(LettaIcons.Add, contentDescription = null, modifier = Modifier.size(LettaDimens.Control.icon))
                Spacer(Modifier.size(LettaDimens.Space.sm))
                Text(ModelControlStrings.ADD_PROVIDER)
            }
        }
    }
}

/** The whole row is the switch: one tap target, announced as a switch with the model's name. */
@Composable
private fun ExposureRow(row: CatalogRow, onExposedChange: (ExposureChange) -> Unit) {
    val name = row.model.model.displayName
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = row.exposed,
                role = Role.Switch,
                onValueChange = { onExposedChange(ExposureChange(row.handle, it)) },
            )
            .semantics { contentDescription = ModelControlStrings.shownInPicker(name, row.exposed) }
            .padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.xs)
            .testTag("${ModelsEditTags.ROW_PREFIX}${row.handle.value}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).alpha(if (row.exposed) 1f else HIDDEN_ALPHA),
        )
        Switch(checked = row.exposed, onCheckedChange = null)
    }
}
