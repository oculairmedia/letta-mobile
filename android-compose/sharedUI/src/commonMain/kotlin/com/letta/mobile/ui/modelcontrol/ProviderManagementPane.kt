package com.letta.mobile.ui.modelcontrol

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.letta.mobile.data.repository.modelcontrol.ConnectableProvider
import com.letta.mobile.data.repository.modelcontrol.ExposureChange
import com.letta.mobile.data.repository.modelcontrol.ModelVisibilityFilter
import com.letta.mobile.data.repository.modelcontrol.ProviderManagementController
import com.letta.mobile.data.repository.modelcontrol.ProviderManagementState
import com.letta.mobile.data.repository.modelcontrol.VisibleSection
import com.letta.mobile.ui.components.LettaEmptyHint
import com.letta.mobile.ui.components.LettaSectionLabel
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens

/** Callbacks of [ProviderManagementPane]; [bind] wires them to the shared controller. */
data class ProviderManagementActions(
    val onRefresh: () -> Unit,
    val onQueryChange: (String) -> Unit,
    val onFilterChange: (ModelVisibilityFilter) -> Unit,
    val onToggleExpanded: (key: String) -> Unit,
    val onExposedChange: (ExposureChange) -> Unit,
    val onProviderExposedChange: (key: String, exposed: Boolean) -> Unit,
    val onConnect: (ConnectableProvider) -> Unit,
    val onDisconnect: (ConnectableProvider) -> Unit,
    val form: ProviderFormActions,
    val onConfirmDisconnect: () -> Unit,
    val onDismissDisconnect: () -> Unit,
) {
    companion object {
        /** The one binding both hosts use; a user refresh bypasses the host's model cache. */
        fun bind(controller: ProviderManagementController) = ProviderManagementActions(
            onRefresh = { controller.refresh(force = true) },
            onQueryChange = controller::setQuery,
            onFilterChange = controller::setFilter,
            onToggleExpanded = controller::toggleExpanded,
            onExposedChange = controller::setExposed,
            onProviderExposedChange = controller::setProviderExposed,
            onConnect = controller::openConnect,
            onDisconnect = controller::requestDisconnect,
            form = ProviderFormActions(
                onChange = controller::updateForm,
                onSubmit = controller::submitConnect,
                onDismiss = controller::dismissForm,
            ),
            onConfirmDisconnect = controller::confirmDisconnect,
            onDismissDisconnect = controller::dismissDisconnect,
        )
    }
}

/**
 * Providers & Models (letta-mobile-w4q4p.6): the host's providers as
 * expandable cards — connected first — each opening onto the models it
 * serves with a show/hide switch per model and show-all / hide-all for the
 * provider. One search covers providers and models; the filter chips narrow
 * to shown or hidden models. Shared by the Android screen and the desktop
 * destination, which only supply a controller.
 */
@Composable
fun ProviderManagementPane(
    state: ProviderManagementState,
    actions: ProviderManagementActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().testTag(ProviderManagementTags.PANE)) {
        if (state.loading || state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        ModelControlNotice(error = state.error, message = state.message)
        PaneToolbar(state, actions)
        val visible = state.visible
        val (connected, available) = visible.partition { it.section.isConnected }
        LazyColumn(
            contentPadding = PaddingValues(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.sm),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (state.sections.isEmpty() && !state.loading) {
                item("empty") { LettaEmptyHint(if (state.error == null) "No providers reported by the host yet" else "Nothing to show") }
            } else if (visible.isEmpty()) {
                item("no-match") { LettaEmptyHint("No providers or models match") }
            }
            providerGroup("Connected", connected, state, actions)
            providerGroup("Available", available, state, actions)
        }
    }
    state.form?.let { ProviderConnectDialog(it, busy = state.busy, actions = actions.form) }
    state.pendingDisconnect?.let { provider ->
        DisconnectConfirmDialog(provider, onConfirm = actions.onConfirmDisconnect, onDismiss = actions.onDismissDisconnect)
    }
}

@Composable
private fun PaneToolbar(state: ProviderManagementState, actions: ProviderManagementActions) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = LettaDimens.Space.lg),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
    ) {
        OutlinedTextField(
            value = state.query,
            onValueChange = actions.onQueryChange,
            placeholder = { Text("Search models or providers") },
            leadingIcon = { Icon(LettaIcons.Search, contentDescription = null) },
            trailingIcon = if (state.query.isEmpty()) {
                null
            } else {
                { IconButton(onClick = { actions.onQueryChange("") }) { Icon(LettaIcons.Clear, contentDescription = "Clear search") } }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(ProviderManagementTags.SEARCH),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
            modifier = Modifier.fillMaxWidth(),
        ) {
            ModelVisibilityFilter.entries.forEach { filter ->
                FilterChip(
                    selected = state.filter == filter,
                    onClick = { actions.onFilterChange(filter) },
                    label = { Text(filter.label) },
                    modifier = Modifier.testTag("${ProviderManagementTags.FILTER_PREFIX}${filter.name.lowercase()}"),
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "${state.shownModels} of ${state.totalModels} shown",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(
                onClick = actions.onRefresh,
                enabled = !state.loading,
                modifier = Modifier.testTag(ProviderManagementTags.REFRESH),
            ) { Icon(LettaIcons.Refresh, contentDescription = "Refresh providers and models") }
        }
    }
}

private val ModelVisibilityFilter.label: String
    get() = when (this) {
        ModelVisibilityFilter.ALL -> "All"
        ModelVisibilityFilter.SHOWN -> "Shown"
        ModelVisibilityFilter.HIDDEN -> "Hidden"
    }

private fun LazyListScope.providerGroup(
    title: String,
    sections: List<VisibleSection>,
    state: ProviderManagementState,
    actions: ProviderManagementActions,
) {
    if (sections.isEmpty()) return
    item("label-$title") { LettaSectionLabel("$title (${sections.size})") }
    sections.forEach { visible -> providerSection(visible, expanded = state.isExpanded(visible.section), busy = state.busy, actions) }
}

/** A section is flat items, not a nested list, so an 80-model provider still scrolls lazily. */
private fun LazyListScope.providerSection(
    visible: VisibleSection,
    expanded: Boolean,
    busy: Boolean,
    actions: ProviderManagementActions,
) {
    val section = visible.section
    item("provider-${section.key}") {
        ProviderHeaderRow(section, expanded = expanded, onClick = { actions.onToggleExpanded(section.key) })
    }
    if (!expanded) return
    item("actions-${section.key}") { ProviderActionsRow(section, busy = busy, actions = actions) }
    if (visible.rows.isEmpty()) {
        item("empty-${section.key}") {
            LettaEmptyHint(if (section.models.isEmpty()) "No models from this provider" else "No models match")
        }
    }
    items(visible.rows, key = { "model-${it.handle.value}" }) { row -> ModelRow(row, onExposedChange = actions.onExposedChange) }
}
