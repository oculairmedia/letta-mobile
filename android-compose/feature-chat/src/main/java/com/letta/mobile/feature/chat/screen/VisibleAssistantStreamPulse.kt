package com.letta.mobile.feature.chat.screen

internal data class VisibleAssistantStreamPulseState(
    val pulse: Long = 0L,
    val tailId: String? = null,
    val contentLength: Int = 0,
)

internal fun reduceVisibleAssistantStreamPulse(
    previous: VisibleAssistantStreamPulseState,
    isStreaming: Boolean,
    tailId: String?,
    contentLength: Int,
): VisibleAssistantStreamPulseState {
    val grew = isStreaming && tailId != null && tailId == previous.tailId && contentLength > previous.contentLength
    return VisibleAssistantStreamPulseState(
        pulse = if (grew) previous.pulse + 1 else previous.pulse,
        tailId = tailId,
        contentLength = contentLength,
    )
}
