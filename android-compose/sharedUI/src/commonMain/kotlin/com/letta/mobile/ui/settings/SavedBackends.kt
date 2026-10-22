package com.letta.mobile.ui.settings

import androidx.compose.runtime.Composable
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.toBackendLabel
import com.letta.mobile.data.settings.SettingsConfirmText
import com.letta.mobile.data.settings.SettingsOption
import com.letta.mobile.data.settings.SettingsRow

/**
 * The backends a host keeps, for a host that keeps more than one: switch to another, add one, or
 * remove one that is not in use. A host with one backend passes none and the Backend section
 * edits that one alone.
 */
class SavedBackends(
    val configs: List<LettaConfig>,
    val activeId: String?,
    val onSwitch: (String) -> Unit,
    /** Saves [LettaConfig] as a new backend beside the others (the host gives it its id). */
    val onAdd: (LettaConfig) -> Unit,
    val onRemove: (String) -> Unit,
)

/** The saved backends as the Backend section shows them, and whether its form is adding a new one. */
class SavedBackendsState(
    val saved: SavedBackends,
    val adding: Boolean,
    val onAddingChange: (Boolean) -> Unit,
)

object SavedBackendsTags {
    const val SAVED = "backend-saved"
    const val ADD = "backend-add"
    const val CANCEL_ADD = "backend-cancel-add"
    const val REMOVE_PREFIX = "backend-remove-"
}

/** The empty remote host the form starts from when adding a backend. */
internal val NewBackend = LettaConfig(id = "", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "")

/**
 * Above the form: which saved backend is in use (a pick switches to it) and Add a backend; while
 * adding, the way back. Below it, each backend not in use can be removed, confirmed first.
 */
@Composable
internal fun SettingsScope.SavedBackendRows(state: SavedBackendsState) {
    val saved = state.saved
    if (state.adding) {
        LinkRow(SettingsRow.Link(SavedBackendsTags.CANCEL_ADD, "Cancel adding a backend", "Back to the backend in use") {
            state.onAddingChange(false)
        })
        return
    }
    if (saved.configs.size > 1) {
        ChoiceRow(
            SettingsRow.Choice(
                key = SavedBackendsTags.SAVED,
                title = "Backend in use",
                options = saved.configs.map { SettingsOption(it.id, it.label(), it.serverUrl.takeIf(String::isNotBlank)) },
                selected = saved.activeId.orEmpty(),
                onSelect = saved.onSwitch,
            ),
        )
    }
    LinkRow(SettingsRow.Link(SavedBackendsTags.ADD, "Add a backend", "Keep another host beside this one") { state.onAddingChange(true) })
}

/** Remove, for each saved backend not in use. */
@Composable
internal fun SettingsScope.RemoveBackendRows(state: SavedBackendsState) {
    if (state.adding) return
    val saved = state.saved
    saved.configs.filter { it.id != saved.activeId }.forEach { config ->
        val label = config.label()
        LinkRow(
            SettingsRow.Link(
                key = SavedBackendsTags.REMOVE_PREFIX + config.id,
                title = "Remove $label",
                destructive = true,
                confirm = SettingsConfirmText("Remove $label?", "This device forgets the backend and its access token.", "Remove"),
                onClick = { saved.onRemove(config.id) },
            ),
        )
    }
}

private fun LettaConfig.label(): String = toBackendLabel() ?: serverUrl
