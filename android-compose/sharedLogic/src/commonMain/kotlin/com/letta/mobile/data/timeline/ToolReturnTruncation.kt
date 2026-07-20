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
/**
 * letta-mobile-c4igq.2: default inbound cap on a single tool-return body folded
 * into the timeline. A server that does NOT pre-project (toolReturnTruncated !=
 * true) can still deliver an arbitrarily large body; storing it in full has
 * wedged a turn mid-stream. We bound it at this shared ingest chokepoint,
 * truncating with a marker (the full body remains fetchable on demand via the
 * ToolReturnTruncation pointer). 128 KiB of UTF-8 text is far larger than any
 * legitimate rendered tool result yet small enough to never wedge a turn.
 */
const val DEFAULT_MAX_INBOUND_TOOL_RETURN_BYTES: Int = 128 * 1024

internal fun foldToolReturnBodies(
    existingContent: Map<String, String>,
    existingTruncation: Map<String, ToolReturnTruncation>,
    matchingReturns: List<Pair<String, ToolReturnMessage>>,
    maxInboundBodyBytes: Int = DEFAULT_MAX_INBOUND_TOOL_RETURN_BYTES,
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
            // letta-mobile-c4igq.2: bound the full body at ingest. An oversized
            // untruncated tool-return (server did not pre-project it) would
            // otherwise be stored whole and could wedge the turn. Truncate to a
            // UTF-8-safe prefix + attach a ToolReturnTruncation marker so the
            // full body stays fetchable on demand; emit observable telemetry.
            val bodyBytes = body.encodeToByteArray()
            if (bodyBytes.size > maxInboundBodyBytes) {
                val boundedPrefix = bodyBytes.decodeToString(0, maxInboundBodyBytes)
                content[callId] = boundedPrefix +
                    "\n… [truncated at ingest: ${bodyBytes.size} bytes > $maxInboundBodyBytes cap — expand to load the full output]"
                truncation[callId] = ToolReturnTruncation(
                    messageId = toolReturn.id,
                    byteLen = bodyBytes.size.toLong(),
                )
                com.letta.mobile.util.Telemetry.event(
                    "TimelineToolReturn", "inbound.truncated",
                    "callId" to callId,
                    "messageId" to toolReturn.id,
                    "byteLen" to bodyBytes.size,
                    "cap" to maxInboundBodyBytes,
                )
            } else {
                content[callId] = body
                truncation.remove(callId)
            }
        }
    }
    return ToolReturnFoldResult(content, truncation)
}
