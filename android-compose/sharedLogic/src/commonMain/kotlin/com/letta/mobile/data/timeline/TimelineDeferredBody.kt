package com.letta.mobile.data.timeline

/**
 * What a stored body turned out to hold, and where to keep reading it from.
 *
 * [field] is null when nothing in the body is showable; [first] is the page the resolution already
 * read, so opening a card costs no extra pass over the body.
 */
data class TimelineDeferredBody(
    val messageType: String,
    val toolName: String?,
    val field: TimelineSemanticField?,
    val first: TimelineSemanticWindowResult.Text?,
    val reason: TimelineSemanticWindowResult.Reason? = null,
)

/**
 * Finds the string a deferred body should show.
 *
 * A tool call keeps its text in one of three places depending on how it finished, and `content` is
 * not one of them: for a tool call that field holds `name(arguments)`, a serialization artifact the
 * normal projection blanks the moment it has structured tool calls to render. Paging it put escaped
 * argument JSON on screen where the tool's output belonged (letta-mobile-jp78k).
 *
 * So the field is discovered rather than assumed. Each attempt is one streaming pass that holds a
 * chunk at a time, and the winner's first page is carried back, so a card opens in at most four
 * passes and turns pages in one.
 */
suspend fun resolveDeferredBody(
    read: suspend (TimelineSemanticField, Long) -> TimelineSemanticWindowResult,
): TimelineDeferredBody {
    val named = read(TimelineSemanticField.toolName(), 0)
    val messageType = when (named) {
        is TimelineSemanticWindowResult.Text -> named.messageType
        is TimelineSemanticWindowResult.Deferred -> named.messageType
    }
    val toolName = (named as? TimelineSemanticWindowResult.Text)?.value?.takeIf { it.isNotBlank() }
    if (named is TimelineSemanticWindowResult.Deferred &&
        named.reason == TimelineSemanticWindowResult.Reason.UnsupportedType
    ) {
        return TimelineDeferredBody(messageType, null, null, null, named.reason)
    }

    var reason: TimelineSemanticWindowResult.Reason? = null
    for (candidate in candidates(messageType)) {
        when (val attempt = read(candidate, 0)) {
            is TimelineSemanticWindowResult.Text ->
                // An empty string is a field that exists and says nothing; keep looking.
                if (attempt.value.isNotEmpty()) {
                    return TimelineDeferredBody(messageType, toolName, candidate, attempt)
                }
            is TimelineSemanticWindowResult.Deferred ->
                // Remember why, but only a budget or shape refusal is worth reporting: a field this
                // event simply does not carry is the normal case for all but one candidate.
                if (attempt.reason != TimelineSemanticWindowResult.Reason.MissingField) reason = attempt.reason
        }
    }
    return TimelineDeferredBody(messageType, toolName, null, null, reason)
}

/**
 * Ordered by where the text actually lives, measured on a real ledger: of the tool calls large
 * enough to be deferred, most carry their output keyed by call id, some carry it flat, and the rest
 * are calls whose arguments were the large part.
 */
private fun candidates(messageType: String): List<TimelineSemanticField> = when (messageType) {
    "tool_call", "tool_call_message", "approval_request_message" -> listOf(
        TimelineSemanticField.ToolReturnByCallId,
        TimelineSemanticField.ToolReturn,
        TimelineSemanticField.toolArguments(),
    )
    "tool_return", "tool_return_message" -> listOf(
        TimelineSemanticField.ToolReturn,
        TimelineSemanticField.ToolReturnByCallId,
        TimelineSemanticField.Content,
    )
    else -> listOf(TimelineSemanticField.Content)
}
