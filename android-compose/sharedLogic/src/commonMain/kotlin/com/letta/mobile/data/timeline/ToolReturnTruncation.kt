package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.ToolReturnMessage

/**
 * letta-mobile-fe51r (P2b pointer diet): marker that a tool-return body held
 * in the timeline is a server-projected preview, not the full output. The
 * full body can be fetched on demand (iroh admin_rpc `tool_return.get`) via
 * [TimelineSyncLoop.resolveTruncatedToolReturn] using [messageId].
 */
data class ToolReturnTruncation(
    /** Server id of the tool_return message the full body lives on. */
    val messageId: String,
    /** UTF-8 byte length of the original (unprojected) body, or -1 if unknown. */
    val byteLen: Long,
)

internal data class ToolReturnFoldResult(
    val contentByCallId: Map<String, String>,
    val truncationByCallId: Map<String, ToolReturnTruncation>,
)

/**
 * Folds tool-return bodies into the per-call-id maps of a TOOL_CALL event,
 * tracking projection state:
 *
 * - A full body always wins and clears any truncation marker for the call.
 * - A projected preview NEVER clobbers a full body we already hold (e.g. the
 *   live stream delivered the full output and a later reconcile page came
 *   back projected).
 *
 * Shared by the hydration reducer, the stream reducer's pending-return
 * attach, and the snapshot reconcile path so all three agree on the rule.
 */
internal fun foldToolReturnBodies(
    existingContent: Map<String, String>,
    existingTruncation: Map<String, ToolReturnTruncation>,
    matchingReturns: List<Pair<String, ToolReturnMessage>>,
): ToolReturnFoldResult {
    if (matchingReturns.isEmpty()) return ToolReturnFoldResult(existingContent, existingTruncation)
    val content = existingContent.toMutableMap()
    val truncation = existingTruncation.toMutableMap()
    for ((callId, toolReturn) in matchingReturns) {
        val body = toolReturn.toolReturn.funcResponse ?: continue
        if (toolReturn.toolReturnTruncated == true) {
            val alreadyHoldsFullBody = callId in content && callId !in truncation
            if (alreadyHoldsFullBody) continue
            content[callId] = body
            truncation[callId] = ToolReturnTruncation(
                messageId = toolReturn.id,
                byteLen = toolReturn.toolReturnByteLen ?: -1L,
            )
        } else {
            content[callId] = body
            truncation.remove(callId)
        }
    }
    return ToolReturnFoldResult(content, truncation)
}
