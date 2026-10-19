package com.letta.mobile.ui.chat.session

import com.letta.mobile.data.repository.modelcontrol.ReasoningEffortChoice
import kotlinx.coroutines.flow.StateFlow

/**
 * A chat host's model extension point, read by a session port for the
 * composer's model chip and the `modelSwitch` capability. [model] is null where the host cannot
 * show or switch the conversation's model (no chip).
 */
interface ChatModelSource {
    /** The conversation's model, its reasoning effort, and what the picker may switch it to. */
    val model: StateFlow<ChatModelUiState?>

    /** Switches the conversation to [handle] (a picker option's) with [effort] for its reasoning effort. */
    fun selectModel(handle: ChatModelHandle, effort: ReasoningEffortChoice)
}
