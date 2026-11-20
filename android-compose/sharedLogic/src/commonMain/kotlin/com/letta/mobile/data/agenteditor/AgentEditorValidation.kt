package com.letta.mobile.data.agenteditor

import com.letta.mobile.data.voice.core.VoiceCore

/** What the agent editor refuses to save, by field, in words the page shows under the field. */
object AgentEditorValidation {
    const val NAME_REQUIRED = "Give the agent a name."
    const val MODEL_REQUIRED = "Pick a model."
    const val CONTEXT_LIMIT_NUMBER = "Enter the context limit as a whole number of tokens."
    val VOICE_DESCRIPTION_TOO_LONG = "Keep the voice description to ${VoiceCore.MAX_LENGTH} characters."

    /** The form's problems; an empty map means it can be saved. A new agent may leave the model to the server default. */
    fun problems(form: AgentEditorForm, mode: AgentEditorMode): Map<AgentEditorField, String> = buildMap {
        if (form.name.isBlank()) put(AgentEditorField.Name, NAME_REQUIRED)
        if (mode is AgentEditorMode.Edit && form.model.isBlank()) put(AgentEditorField.Model, MODEL_REQUIRED)
        contextLimitProblem(form.contextLimit)?.let { put(AgentEditorField.ContextLimit, it) }
        voiceDescriptionProblem(form.voiceDescription)?.let { put(AgentEditorField.VoiceDescription, it) }
    }

    /** Null when [text] fits a voice block once sanitised ([VoiceCore.blockValueOf]); blank clears it. */
    fun voiceDescriptionProblem(text: String): String? =
        VOICE_DESCRIPTION_TOO_LONG.takeIf { VoiceCore.blockValueOf(text) == null }

    /** Blank (the model's own window) or a positive whole number of tokens. */
    fun contextLimitProblem(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return CONTEXT_LIMIT_NUMBER.takeIf { trimmed.toIntOrNull()?.takeIf { it > 0 } == null }
    }

    /** The context limit to send: null when blank (or not a number, which validation already refused). */
    fun contextLimitOf(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it > 0 }
}
