package com.letta.mobile.data.chat.runtime

data class PromptComposerState(
    val text: String = "",
    val status: PromptComposerStatus = PromptComposerStatus.Ready,
    val attachments: List<PromptComposerAttachment> = emptyList(),
    val template: PromptComposerTemplate? = null,
    val model: PromptComposerModelMetadata? = null,
) {
    val ready: Boolean
        get() = status == PromptComposerStatus.Ready

    val isSending: Boolean
        get() = status == PromptComposerStatus.Sending

    val isStreaming: Boolean
        get() = status == PromptComposerStatus.Streaming

    val hasPayload: Boolean
        get() = text.isNotBlank() || attachments.isNotEmpty() || template != null

    val canSend: Boolean
        get() = ready && hasPayload

    val canStop: Boolean
        get() = isSending || isStreaming

    fun updateText(value: String): PromptComposerState = copy(text = value)

    fun updateAttachments(value: List<PromptComposerAttachment>): PromptComposerState = copy(attachments = value)

    fun selectTemplate(value: PromptComposerTemplate?): PromptComposerState = copy(template = value)

    fun selectModel(value: PromptComposerModelMetadata?): PromptComposerState = copy(model = value)

    fun beginSend(): PromptComposerSendTransition? {
        if (!canSend) return null
        return PromptComposerSendTransition(
            payload = PromptComposerOutgoingPayload(
                text = text.trim(),
                attachments = attachments,
                template = template,
                model = model,
            ),
            nextState = PromptComposerState(status = PromptComposerStatus.Sending),
        )
    }

    fun beginStreaming(): PromptComposerState =
        if (status == PromptComposerStatus.Sending) copy(status = PromptComposerStatus.Streaming) else this

    fun stop(): PromptComposerState = if (canStop) copy(status = PromptComposerStatus.Ready) else this
}

enum class PromptComposerStatus {
    Ready,
    Sending,
    Streaming,
}

data class PromptComposerOutgoingPayload(
    val text: String,
    val attachments: List<PromptComposerAttachment> = emptyList(),
    val template: PromptComposerTemplate? = null,
    val model: PromptComposerModelMetadata? = null,
)

data class PromptComposerSendTransition(
    val payload: PromptComposerOutgoingPayload,
    val nextState: PromptComposerState,
)

data class PromptComposerAttachment(
    val id: String,
    val kind: String,
    val metadata: Map<String, String> = emptyMap(),
)

data class PromptComposerTemplate(
    val id: String,
    val label: String? = null,
    val variables: Map<String, String> = emptyMap(),
)

data class PromptComposerModelMetadata(
    val id: String,
    val label: String? = null,
    val route: String? = null,
)
