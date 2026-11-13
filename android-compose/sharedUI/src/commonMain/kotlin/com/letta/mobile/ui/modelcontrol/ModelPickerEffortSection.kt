package com.letta.mobile.ui.modelcontrol

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.letta.mobile.data.composer.ComposerEffort
import com.letta.mobile.data.repository.modelcontrol.ReasoningEffortChoice
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.composer_effort_default
import com.letta.mobile.sharedui.resources.composer_effort_group
import com.letta.mobile.sharedui.resources.composer_effort_header
import com.letta.mobile.sharedui.resources.composer_effort_unavailable
import com.letta.mobile.ui.components.LettaChipTab
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LettaSectionLabel
import org.jetbrains.compose.resources.stringResource

/**
 * The current model's reasoning effort, offered under the picker's list by a host that has one to
 * change (the composer's model control). [efforts] are wire literals in ladder order; empty, the
 * section says the model has none instead of offering choices. [current] is the effort in force
 * (null: the provider default); [onSelect] applies a choice.
 */
@Immutable
internal data class ModelPickerEffortSection(
    val modelLabel: String,
    val efforts: List<String>,
    val current: String?,
    val onSelect: (ReasoningEffortChoice) -> Unit,
)

/** The section the open picker shows under its list; null shows none. */
internal val LocalModelPickerEffortSection = staticCompositionLocalOf<ModelPickerEffortSection?> { null }

/** Test tags of [ModelPickerEffortSection]'s row. */
internal object ModelPickerEffortTags {
    const val SECTION = "model_picker_effort_section"
    const val GROUP = "model_picker_effort_group"

    /** One choice, followed by its wire literal, or [DEFAULT_CHOICE] for the provider default. */
    const val CHOICE_PREFIX = "model_picker_effort_choice_"
    const val DEFAULT_CHOICE = "default"
    const val UNAVAILABLE = "model_picker_effort_unavailable"
}

/**
 * EFFORT with one segment per choice, "Default" first, the one in force raised; or, for a model
 * without effort levels, why there is nothing to pick.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModelPickerEffortRow(section: ModelPickerEffortSection) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LettaDimens.Space.sm, vertical = LettaDimens.Space.xs)
            .testTag(ModelPickerEffortTags.SECTION),
    ) {
        LettaSectionLabel(stringResource(Res.string.composer_effort_header), Modifier.semantics { heading() })
        if (section.efforts.isEmpty()) {
            Text(
                text = stringResource(Res.string.composer_effort_unavailable, section.modelLabel),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = LettaDimens.Space.xs, vertical = LettaDimens.Space.xs)
                    .testTag(ModelPickerEffortTags.UNAVAILABLE),
            )
            return@Column
        }
        val groupLabel = stringResource(Res.string.composer_effort_group, section.modelLabel)
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = LettaDimens.Space.xs)
                .selectableGroup()
                .semantics { contentDescription = groupLabel }
                .testTag(ModelPickerEffortTags.GROUP),
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
        ) {
            EffortChoice(
                text = stringResource(Res.string.composer_effort_default),
                active = section.current == null,
                key = ModelPickerEffortTags.DEFAULT_CHOICE,
            ) { section.onSelect(ReasoningEffortChoice.ProviderDefault) }
            section.efforts.forEach { effort ->
                EffortChoice(text = ComposerEffort.labelOf(effort), active = effort == section.current, key = effort) {
                    section.onSelect(ReasoningEffortChoice.Named(effort))
                }
            }
        }
    }
}

@Composable
private fun EffortChoice(text: String, active: Boolean, key: String, onClick: () -> Unit) {
    LettaChipTab(
        text = text,
        active = active,
        onClick = onClick,
        modifier = Modifier.testTag(ModelPickerEffortTags.CHOICE_PREFIX + key),
    )
}
