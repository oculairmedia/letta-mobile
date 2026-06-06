package com.letta.mobile.feature.chat.render

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.letta.mobile.data.model.UiGeneratedComponent
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiSubagentDispatch
import com.letta.mobile.data.model.UiToolCall
import java.util.LinkedHashMap
import kotlin.math.roundToInt
import com.letta.mobile.feature.chat.coordination.ChatRenderItem

private const val GeometryFullHashMaxChars = 96
private const val GeometrySampleWindowChars = 24

internal data class ChatMessageGeometryBucket(
    val renderKey: String,
    val widthPx: Int,
    val densityBucket: Int,
    val fontScaleBucket: Int,
    val chatFontScaleBucket: Int,
    val layoutDirection: LayoutDirection,
    val chatMode: String,
    val expansionHash: Int,
)

internal data class ChatRenderItemGeometrySignature(
    val bucket: ChatMessageGeometryBucket,
    val contentLength: Int,
    val contentHash: Int,
)

internal class ChatMessageGeometryState(
    private val maxEntries: Int = 240,
) {
    private val exactHeights = object : LinkedHashMap<ChatRenderItemGeometrySignature, Int>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ChatRenderItemGeometrySignature, Int>?): Boolean =
            size > maxEntries
    }
    private val streamingFloors = LinkedHashMap<ChatMessageGeometryBucket, Int>()

    fun heightFloorFor(
        signature: ChatRenderItemGeometrySignature,
        isStreaming: Boolean,
    ): Int {
        val exact = exactHeights[signature] ?: 0
        val streaming = if (isStreaming) streamingFloors[signature.bucket] ?: 0 else 0
        return maxOf(exact, streaming)
    }

    fun recordMeasuredHeight(
        signature: ChatRenderItemGeometrySignature,
        heightPx: Int,
        isStreaming: Boolean,
    ) {
        val safeHeight = heightPx.coerceAtLeast(0)
        if (isStreaming) {
            val current = streamingFloors[signature.bucket] ?: 0
            if (safeHeight > current) {
                streamingFloors[signature.bucket] = safeHeight
            }
            return
        }
        exactHeights[signature] = safeHeight
    }

    fun retainStreamingBuckets(activeBuckets: Set<ChatMessageGeometryBucket>) {
        if (activeBuckets.isEmpty()) {
            streamingFloors.clear()
            return
        }
        streamingFloors.keys.removeAll { it !in activeBuckets }
    }

    fun exactSize(): Int = exactHeights.size
}

internal fun ChatRenderItem.chatGeometrySignature(
    state: ChatUiState,
    chatMode: String,
    widthPx: Int,
    density: Density,
    layoutDirection: LayoutDirection,
    activeFontScale: Float,
): ChatRenderItemGeometrySignature {
    val expansionHash = geometryExpansionHash(state)
    val contentFingerprint = geometryContentFingerprint(state)
    return ChatRenderItemGeometrySignature(
        bucket = ChatMessageGeometryBucket(
            renderKey = key,
            widthPx = widthPx,
            densityBucket = (density.density * 1000).roundToInt(),
            fontScaleBucket = (density.fontScale * 1000).roundToInt(),
            chatFontScaleBucket = (activeFontScale * 1000).roundToInt(),
            layoutDirection = layoutDirection,
            chatMode = chatMode,
            expansionHash = expansionHash,
        ),
        contentLength = contentFingerprint.length,
        contentHash = contentFingerprint.hash,
    )
}

private data class ChatRenderItemContentFingerprint(
    val length: Int,
    val hash: Int,
)

private fun ChatRenderItem.geometryContentFingerprint(state: ChatUiState): ChatRenderItemContentFingerprint {
    var length = 0
    var hash = key.hashCode()

    fun include(value: Int) {
        hash = 31 * hash + value
    }

    fun includeMessage(message: UiMessage) {
        length += message.content.length
        include(message.id.hashCode())
        include(message.role.hashCode())
        include(message.content.length)
        include(message.content.geometryTextHash())
        include(message.timestamp.hashCode())
        include(message.runId.hashCode())
        include(message.stepId.hashCode())
        include(message.isPending.hashCode())
        include(message.isReasoning.hashCode())
        include(message.isError.hashCode())
        include(message.latencyMs.hashCode())
        include(message.toolCalls.geometryToolCallsHash())
        include(message.generatedUi.geometryGeneratedUiHash())
        include(message.approvalRequest.hashCode())
        include(message.approvalResponse.hashCode())
        include(message.attachments.size)
        include(message.attachments.geometryAttachmentsHash())
        include((message.id !in state.expandedReasoningMessageIds).hashCode())
        include((state.activeApprovalRequestId == message.approvalRequest?.requestId).hashCode())
    }

    when (this) {
        is ChatRenderItem.Single -> {
            includeMessage(message)
            include(groupPosition.hashCode())
            include(stableRunKey.hashCode())
            stableRunId?.let { runId ->
                include((runId in state.collapsedRunIds).hashCode())
            }
        }
        is ChatRenderItem.RunBlock -> {
            include(runId.hashCode())
            include((runId in state.collapsedRunIds).hashCode())
            for ((message, position) in messages) {
                includeMessage(message)
                include(position.hashCode())
            }
        }
    }
    return ChatRenderItemContentFingerprint(length = length, hash = hash)
}

private fun ChatRenderItem.geometryExpansionHash(state: ChatUiState): Int {
    var hash = 17
    fun include(value: Int) {
        hash = 31 * hash + value
    }

    when (this) {
        is ChatRenderItem.Single -> {
            include(groupPosition.hashCode())
            include(stableRunKey.hashCode())
            include((message.id !in state.expandedReasoningMessageIds).hashCode())
            stableRunId?.let { runId ->
                include((runId in state.collapsedRunIds).hashCode())
            }
        }
        is ChatRenderItem.RunBlock -> {
            include(runId.hashCode())
            include((runId in state.collapsedRunIds).hashCode())
            messages.forEach { (message, position) ->
                include(position.hashCode())
                include((message.id !in state.expandedReasoningMessageIds).hashCode())
            }
        }
    }
    return hash
}

private fun String.geometryTextHash(): Int {
    if (length <= GeometryFullHashMaxChars) return hashCode()
    var hash = length

    fun include(value: Int) {
        hash = 31 * hash + value
    }

    fun includeRange(start: Int, endExclusive: Int) {
        for (index in start until endExclusive.coerceAtMost(length)) {
            include(this[index].code)
        }
    }

    includeRange(0, GeometrySampleWindowChars)
    val middleStart = (length / 2 - GeometrySampleWindowChars / 2)
        .coerceIn(0, length)
    includeRange(middleStart, middleStart + GeometrySampleWindowChars)
    includeRange((length - GeometrySampleWindowChars).coerceAtLeast(0), length)
    include(this[length / 4].code)
    include(this[length / 2].code)
    include(this[(length * 3) / 4].code)
    return hash
}

private fun String?.geometryNullableTextHash(): Int = this?.geometryTextHash() ?: 0

private fun List<UiToolCall>?.geometryToolCallsHash(): Int {
    if (isNullOrEmpty()) return 0
    var hash = size
    fun include(value: Int) {
        hash = 31 * hash + value
    }
    for (toolCall in this) {
        include(toolCall.name.hashCode())
        include(toolCall.arguments.geometryTextHash())
        include(toolCall.result.geometryNullableTextHash())
        include(toolCall.status.hashCode())
        include(toolCall.generatedImageAttachments.geometryAttachmentsHash())
        include(toolCall.executionTimeMs.hashCode())
        include(toolCall.toolCallId.hashCode())
        include(toolCall.approvalDecision.hashCode())
        include(toolCall.subagentDispatch.geometrySubagentDispatchHash())
    }
    return hash
}

private fun UiSubagentDispatch?.geometrySubagentDispatchHash(): Int {
    if (this == null) return 0
    var hash = toolCallId.hashCode()
    fun include(value: Int) {
        hash = 31 * hash + value
    }
    include(description.geometryTextHash())
    include(subagentType.hashCode())
    include(runInBackground.hashCode())
    include(prompt.geometryTextHash())
    include(taskId.hashCode())
    include(subagentAgentId.hashCode())
    return hash
}

private fun UiGeneratedComponent?.geometryGeneratedUiHash(): Int {
    if (this == null) return 0
    var hash = name.hashCode()
    hash = 31 * hash + propsJson.geometryTextHash()
    hash = 31 * hash + fallbackText.geometryNullableTextHash()
    return hash
}

private fun List<UiImageAttachment>.geometryAttachmentsHash(): Int {
    if (isEmpty()) return 0
    var hash = size
    for (attachment in this) {
        hash = 31 * hash + attachment.mediaType.hashCode()
        hash = 31 * hash + attachment.base64.length
        hash = 31 * hash + attachment.base64.geometryTextHash()
    }
    return hash
}
