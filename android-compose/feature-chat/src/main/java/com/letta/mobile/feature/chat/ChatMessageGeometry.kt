package com.letta.mobile.feature.chat

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.letta.mobile.data.model.UiMessage
import java.util.LinkedHashMap
import kotlin.math.roundToInt

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
        include(message.content.hashCode())
        include(message.timestamp.hashCode())
        include(message.runId.hashCode())
        include(message.stepId.hashCode())
        include(message.isPending.hashCode())
        include(message.isReasoning.hashCode())
        include(message.isError.hashCode())
        include(message.latencyMs.hashCode())
        include(message.toolCalls.hashCode())
        include(message.generatedUi.hashCode())
        include(message.approvalRequest.hashCode())
        include(message.approvalResponse.hashCode())
        include(message.attachments.size)
        include(message.attachments.fold(1) { acc, attachment ->
            31 * acc + attachment.mediaType.hashCode() + attachment.base64.length
        })
        include((message.id !in state.expandedReasoningMessageIds).hashCode())
        include((state.activeApprovalRequestId == message.approvalRequest?.requestId).hashCode())
    }

    when (this) {
        is ChatRenderItem.Single -> {
            includeMessage(message)
            include(groupPosition.hashCode())
            include(stableRunKey.hashCode())
            stableRunKey?.removePrefix("run-")?.let { runId ->
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
            stableRunKey?.removePrefix("run-")?.let { runId ->
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
