package com.letta.mobile.ui.common

enum class GroupPosition { First, Middle, Last, None }

data class BubbleState(
    val groupPosition: GroupPosition,
    val isMine: Boolean,
)

fun <T> groupMessages(
    messages: List<T>,
    getRole: (T) -> String,
    getTimestamp: (T) -> String,
): List<Pair<T, GroupPosition>> {
    if (messages.isEmpty()) return emptyList()
    if (messages.size == 1) return listOf(messages[0] to GroupPosition.None)

    return messages.mapIndexed { index, message ->
        val prevRole = messages.getOrNull(index - 1)?.let(getRole)
        val nextRole = messages.getOrNull(index + 1)?.let(getRole)
        val currentRole = getRole(message)

        val sameAsPrev = prevRole == currentRole
        val sameAsNext = nextRole == currentRole

        val position = when {
            sameAsPrev && sameAsNext -> GroupPosition.Middle
            !sameAsPrev && sameAsNext -> GroupPosition.First
            sameAsPrev && !sameAsNext -> GroupPosition.Last
            else -> GroupPosition.None
        }
        message to position
    }
}
