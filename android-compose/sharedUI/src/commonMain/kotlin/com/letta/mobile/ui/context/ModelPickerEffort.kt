package com.letta.mobile.ui.context

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.repository.modelcontrol.ReasoningEffortChoice

/**
 * One effort the picker offers for the current model: [key] identifies it ("default" or the wire
 * literal), [label] is what it reads, [selected] marks the one in force, and [choice] is what
 * picking it applies.
 */
@Immutable
internal data class ModelPickerEffortChoice(
    val key: String,
    val label: String,
    val selected: Boolean,
    val choice: ReasoningEffortChoice,
) {
    companion object {
        /** The [key] of the provider-default choice. */
        const val DEFAULT_KEY = "default"
    }
}

/**
 * The current model's reasoning effort, offered under the picker's list by a host that has one to
 * change (the composer's model control), in words the host already chose: the section's [header],
 * the [groupLabel] announced for the choices, and the [choices] in display order. Where the model has
 * no effort levels, [unavailableReason] says so and [choices] is empty. [onSelect] applies a choice.
 */
@Immutable
internal data class ModelPickerEffortSection(
    val header: String,
    val groupLabel: String,
    val choices: List<ModelPickerEffortChoice>,
    val unavailableReason: String?,
    val onSelect: (ReasoningEffortChoice) -> Unit,
)
