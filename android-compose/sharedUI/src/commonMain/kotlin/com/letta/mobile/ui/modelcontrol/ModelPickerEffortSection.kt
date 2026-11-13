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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.letta.mobile.ui.components.LettaChipTab
import com.letta.mobile.ui.context.ModelPickerEffortChoice
import com.letta.mobile.ui.context.ModelPickerEffortSection
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LettaSectionLabel

/** The section the open picker shows under its list; null shows none. */
internal val LocalModelPickerEffortSection = staticCompositionLocalOf<ModelPickerEffortSection?> { null }

/** Test tags of [ModelPickerEffortRow]. */
internal object ModelPickerEffortTags {
    const val SECTION = "model_picker_effort_section"
    const val GROUP = "model_picker_effort_group"

    /** One choice, followed by its key: the wire literal, or [DEFAULT_CHOICE] for the provider default. */
    const val CHOICE_PREFIX = "model_picker_effort_choice_"
    const val DEFAULT_CHOICE = ModelPickerEffortChoice.DEFAULT_KEY
    const val UNAVAILABLE = "model_picker_effort_unavailable"
}

/**
 * The section's header with one segment per choice, the one in force raised; or, for a model without
 * effort levels, why there is nothing to pick.
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
        LettaSectionLabel(section.header, Modifier.semantics { heading() })
        val reason = section.unavailableReason
        if (reason != null) {
            Text(
                text = reason,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = LettaDimens.Space.xs, vertical = LettaDimens.Space.xs)
                    .testTag(ModelPickerEffortTags.UNAVAILABLE),
            )
            return@Column
        }
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = LettaDimens.Space.xs)
                .selectableGroup()
                .semantics { contentDescription = section.groupLabel }
                .testTag(ModelPickerEffortTags.GROUP),
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
        ) {
            section.choices.forEach { option ->
                LettaChipTab(
                    text = option.label,
                    active = option.selected,
                    onClick = { section.onSelect(option.choice) },
                    modifier = Modifier.testTag(ModelPickerEffortTags.CHOICE_PREFIX + option.key),
                )
            }
        }
    }
}
