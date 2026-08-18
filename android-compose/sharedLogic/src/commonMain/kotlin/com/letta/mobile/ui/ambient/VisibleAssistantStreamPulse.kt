package com.letta.mobile.ui.ambient

data class VisibleAssistantStreamPulseState(
    val pulse: Long = 0L,
    val tailId: String? = null,
    val contentLength: Int = 0,
)

fun reduceVisibleAssistantStreamPulse(
    previous: VisibleAssistantStreamPulseState,
    isStreaming: Boolean,
    tailId: String?,
    contentLength: Int,
): VisibleAssistantStreamPulseState {
    val sameTail = tailId != null && tailId == previous.tailId
    val grew = isStreaming && sameTail && contentLength > previous.contentLength
    return VisibleAssistantStreamPulseState(
        pulse = if (grew) previous.pulse + 1 else previous.pulse,
        tailId = tailId,
        contentLength = if (sameTail) maxOf(previous.contentLength, contentLength) else contentLength,
    )
}
