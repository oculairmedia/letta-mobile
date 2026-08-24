package com.letta.mobile.feature.chat.screen.messagelist

import android.view.Choreographer
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.zoom.PinchScalePreviewController
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

internal data class ChatPinchVisibleContentSummary(
    val userMessages: Int,
    val assistantMessages: Int,
    val toolCards: Int,
    val runBlocks: Int,
)

internal data class ChatLoadPressureSummary(
    val messageCount: Int,
    val renderItemCount: Int,
    val isStreaming: Boolean,
    val isLoadingMessages: Boolean,
    val isLoadingOlderMessages: Boolean,
    val toolCardCount: Int,
) {
    val isHydrating: Boolean = isLoadingMessages
    val isReconciling: Boolean = false
}

internal data class ChatPinchGestureRuntime(
    val listState: androidx.compose.foundation.lazy.LazyListState,
    val activeFontScale: Float,
    val currentRenderItems: List<ChatRenderItem>,
    val currentLoadPressureSummary: ChatLoadPressureSummary,
    val callbacks: ChatMessageListCallbacks,
    val pinchFontScaleController: PinchScalePreviewController,
    val pinchAnchorState: ChatPinchAnchorState,
    val compensationRequests: Channel<ChatPinchCompensationRequest>,
    val pinchFrameBudgetSampler: ChatPinchFrameBudgetSampler,
    val onPinchTick: (Long) -> Unit,
    val onPinchAnimationSuppressionTick: (Long) -> Unit,
    val onSuppressPinchLayoutAnimations: (Boolean) -> Unit,
    val scope: CoroutineScope,
)

internal data class ChatPinchFrameBudgetStartContext(
    val visibleItems: Int,
    val totalItems: Int,
    val visibleContent: ChatPinchVisibleContentSummary,
    val loadPressure: ChatLoadPressureSummary,
    val committedScale: Float,
)

internal data class ChatPinchZoomContext(
    val event: PointerEvent,
    val pinchFontScaleController: PinchScalePreviewController,
)

internal data class ChatPinchFrameBudgetStopContext(
    val committedScale: Float,
    val targetScale: Float,
)

internal data class ChatPinchFrameBudgetFinishedContext(
    val frames: List<Long>,
    val elapsedMs: Long,
    val scales: ChatPinchFrameBudgetStopContext,
)

internal data class ChatPinchFrameBudgetEmptyFinishedContext(
    val elapsedMs: Long,
    val scales: ChatPinchFrameBudgetStopContext,
)

internal enum class ChatPinchCompensationRequest {
    Layout,
    CommitLayout,
}

internal data class ChatPinchLayoutSignature(
    val anchorKey: Any?,
    val anchorIndex: Int?,
    val anchorTopPx: Int?,
    val anchorHeightPx: Int?,
    val totalItems: Int,
)

internal fun shouldClearChatPinchAnchorAfterSettle(
    request: ChatPinchCompensationRequest,
    settled: Boolean,
): Boolean = request == ChatPinchCompensationRequest.CommitLayout && settled

internal data class ChatPinchGestureBoxParams(
    val listState: androidx.compose.foundation.lazy.LazyListState,
    val activeFontScale: Float,
    val currentRenderItems: List<ChatRenderItem>,
    val currentLoadPressureSummary: ChatLoadPressureSummary,
    val callbacks: ChatMessageListCallbacks,
    val pinchFontScaleController: PinchScalePreviewController,
    val pinchAnchorState: ChatPinchAnchorState,
    val pinchFrameBudgetSampler: ChatPinchFrameBudgetSampler,
    val onPinchTick: (Long) -> Unit,
    val onPinchAnimationSuppressionTick: (Long) -> Unit,
    val onSuppressPinchLayoutAnimations: (Boolean) -> Unit,
    val scope: CoroutineScope,
)

@Composable
internal fun ChatMessageListPinchGestureBox(
    params: ChatPinchGestureBoxParams,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val compensationRequests = remember {
        Channel<ChatPinchCompensationRequest>(capacity = Channel.CONFLATED)
    }
    val runtime = ChatPinchGestureRuntime(
        listState = params.listState,
        activeFontScale = params.activeFontScale,
        currentRenderItems = params.currentRenderItems,
        currentLoadPressureSummary = params.currentLoadPressureSummary,
        callbacks = params.callbacks,
        pinchFontScaleController = params.pinchFontScaleController,
        pinchAnchorState = params.pinchAnchorState,
        compensationRequests = compensationRequests,
        pinchFrameBudgetSampler = params.pinchFrameBudgetSampler,
        onPinchTick = params.onPinchTick,
        onPinchAnimationSuppressionTick = params.onPinchAnimationSuppressionTick,
        onSuppressPinchLayoutAnimations = params.onSuppressPinchLayoutAnimations,
        scope = params.scope,
    )
    ChatPinchAnchorCompensationEffect(runtime)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .chatMessageListPinchGesture(runtime),
    ) {
        content()
    }
}

@Composable
private fun ChatPinchAnchorCompensationEffect(runtime: ChatPinchGestureRuntime) {
    LaunchedEffect(runtime.listState, runtime.compensationRequests) {
        launch {
            snapshotFlow { runtime.listState.currentPinchLayoutSignature(runtime.pinchAnchorState.anchor?.key) }
                .distinctUntilChanged()
                .collect {
                    if (runtime.pinchAnchorState.anchor != null) {
                        runtime.compensationRequests.trySend(ChatPinchCompensationRequest.Layout)
                    }
                }
        }
        runtime.compensationRequests.receiveAsFlow().collect { request ->
            val settled = settleChatPinchAnchor(runtime)
            if (shouldClearChatPinchAnchorAfterSettle(request, settled)) {
                completeCommittedChatPinch(runtime)
            }
        }
    }
}

private suspend fun settleChatPinchAnchor(runtime: ChatPinchGestureRuntime): Boolean {
    repeat(MAX_PINCH_SETTLE_PASSES) {
        withFrameNanos { }
        val correction = runtime.pinchAnchorState.correction(runtime.listState.currentVisibleItemBounds())
        if (correction.anchor == null || correction.deltaPx == 0f) return true
        runtime.listState.dispatchRawDelta(correction.deltaPx)
    }
    val residual = runtime.pinchAnchorState.correction(runtime.listState.currentVisibleItemBounds())
    return residual.anchor == null || residual.deltaPx == 0f
}

private fun completeCommittedChatPinch(runtime: ChatPinchGestureRuntime) {
    runtime.pinchAnchorState.finish()
    runtime.onSuppressPinchLayoutAnimations(false)
    runtime.onPinchAnimationSuppressionTick(System.nanoTime())
}

private fun androidx.compose.foundation.lazy.LazyListState.currentVisibleItemBounds(): List<ChatVisibleItemBounds> =
    layoutInfo.visibleItemsInfo.map {
        ChatVisibleItemBounds(it.key, it.index, it.offset, it.offset + it.size)
    }

private fun androidx.compose.foundation.lazy.LazyListState.currentPinchLayoutSignature(anchorKey: Any?): ChatPinchLayoutSignature {
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.key == anchorKey }
    return ChatPinchLayoutSignature(
        anchorKey = item?.key,
        anchorIndex = item?.index,
        anchorTopPx = item?.offset,
        anchorHeightPx = item?.size,
        totalItems = layoutInfo.totalItemsCount,
    )
}

private const val MAX_PINCH_SETTLE_PASSES = 4

private fun Modifier.chatMessageListPinchGesture(runtime: ChatPinchGestureRuntime): Modifier =
    pointerInput(runtime) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var gesturePinching = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    if (!gesturePinching) {
                        gesturePinching = startChatPinchGesture(runtime, event.calculateCentroid().y)
                    }
                    if (!gesturePinching) continue
                    applyChatPinchZoom(
                        ChatPinchZoomContext(
                            event = event,
                            pinchFontScaleController = runtime.pinchFontScaleController,
                        ),
                    )
                }
            } while (event.changes.any { it.pressed })
            finishChatPinchGesture(runtime, gesturePinching)
        }
    }

private fun startChatPinchGesture(runtime: ChatPinchGestureRuntime, centroidYPx: Float): Boolean {
    val anchor = runtime.pinchAnchorState.begin(
        centroidYPx = centroidYPx,
        visibleItems = runtime.listState.layoutInfo.visibleItemsInfo.map {
            ChatVisibleItemBounds(
                key = it.key,
                index = it.index,
                topPx = it.offset,
                bottomPx = it.offset + it.size,
            )
        },
    ) ?: return false
    if (anchor.key !in runtime.currentRenderItems.map { it.key }) {
        runtime.pinchAnchorState.finish()
        return false
    }
    runtime.onSuppressPinchLayoutAnimations(true)
    runtime.onPinchAnimationSuppressionTick(0L)
    runtime.pinchFontScaleController.begin(runtime.activeFontScale)
    val visibleContent = visiblePinchContent(runtime)
    val loadPressureForSample = runtime.currentLoadPressureSummary.copy(
        toolCardCount = runtime.currentRenderItems.pinchVisibleContentSummary().toolCards,
    )
    runtime.pinchFrameBudgetSampler.start(
        ChatPinchFrameBudgetStartContext(
            visibleItems = runtime.listState.layoutInfo.visibleItemsInfo.size,
            totalItems = runtime.listState.layoutInfo.totalItemsCount,
            visibleContent = visibleContent,
            loadPressure = loadPressureForSample,
            committedScale = runtime.activeFontScale,
        ),
    )
    runtime.onPinchTick(System.nanoTime())
    return true
}

private fun visiblePinchContent(runtime: ChatPinchGestureRuntime): ChatPinchVisibleContentSummary {
    val renderItemsByKey = runtime.currentRenderItems.associateBy { it.key }
    val visibleRenderItems = runtime.listState.layoutInfo.visibleItemsInfo.mapNotNull { itemInfo ->
        renderItemsByKey[itemInfo.key]
    }
    return visibleRenderItems.pinchVisibleContentSummary()
}

private fun applyChatPinchZoom(context: ChatPinchZoomContext) {
    val zoom = context.event.calculateZoom()
    if (zoom != 1f) {
        context.event.changes.forEach { it.consume() }
        context.pinchFontScaleController.applyZoom(zoom)
    }
}

private fun finishChatPinchGesture(runtime: ChatPinchGestureRuntime, gesturePinching: Boolean) {
    if (gesturePinching) {
        finishActiveChatPinchGesture(runtime)
        return
    }
    cancelChatPinchGesture(runtime)
}

private fun finishActiveChatPinchGesture(runtime: ChatPinchGestureRuntime) {
    val snapped = runtime.pinchFontScaleController.finishPreview()
    runtime.callbacks.onActiveFontScaleChange(snapped)
    runtime.callbacks.onFontScaleChange(snapped)
    runtime.scope.launch {
        withFrameNanos { }
        runtime.compensationRequests.send(ChatPinchCompensationRequest.CommitLayout)
    }
    runtime.pinchFrameBudgetSampler.stop(
        ChatPinchFrameBudgetStopContext(
            committedScale = runtime.activeFontScale,
            targetScale = snapped,
        ),
    )
    runtime.onPinchTick(System.nanoTime())
}

private fun cancelChatPinchGesture(runtime: ChatPinchGestureRuntime) {
    runtime.pinchFontScaleController.cancel()
    runtime.pinchAnchorState.finish()
    runtime.pinchFrameBudgetSampler.cancel()
    runtime.onSuppressPinchLayoutAnimations(false)
}

internal fun Collection<ChatRenderItem>.pinchVisibleContentSummary(): ChatPinchVisibleContentSummary {
    val counts = ChatPinchVisibleContentCounts()
    for (item in this) {
        counts.accumulate(item)
    }
    return counts.toSummary()
}

private class ChatPinchVisibleContentCounts {
    var userMessages = 0
    var assistantMessages = 0
    var toolCards = 0
    var runBlocks = 0

    fun accumulate(item: ChatRenderItem) {
        when (item) {
            is ChatRenderItem.Single -> countMessage(item.message)
            is ChatRenderItem.RunBlock -> {
                runBlocks++
                item.messages.forEach { (message, _) -> countMessage(message) }
            }
            is ChatRenderItem.SkillEnvelopeChip -> Unit
        }
    }

    private fun countMessage(message: UiMessage) {
        when (PinchMessageRole.from(message.role)) {
            PinchMessageRole.User -> userMessages++
            PinchMessageRole.Assistant -> assistantMessages++
            PinchMessageRole.Other -> Unit
        }
        if (message.isPinchToolCard()) {
            toolCards++
        }
    }

    fun toSummary() = ChatPinchVisibleContentSummary(
        userMessages = userMessages,
        assistantMessages = assistantMessages,
        toolCards = toolCards,
        runBlocks = runBlocks,
    )
}

private enum class PinchMessageRole {
    User,
    Assistant,
    Other,
    ;

    companion object {
        fun from(raw: String): PinchMessageRole = when (raw) {
            "user" -> User
            "assistant" -> Assistant
            else -> Other
        }
    }
}

private fun UiMessage.isPinchToolCard(): Boolean =
    role == "tool" || !toolCalls.isNullOrEmpty() || generatedUi != null

internal class ChatPinchFrameBudgetSampler {
    private val frameDurationsMs = ArrayList<Long>(240)
    private var choreographer: Choreographer? = null
    private var startedAtMs = 0L
    private var lastFrameTimeNanos = 0L
    private var visibleItems = 0
    private var totalItems = 0
    private var visibleUserMessages = 0
    private var visibleAssistantMessages = 0
    private var visibleToolCards = 0
    private var visibleRunBlocks = 0
    private var loadPressure = ChatLoadPressureSummary(
        messageCount = 0,
        renderItemCount = 0,
        isStreaming = false,
        isLoadingMessages = false,
        isLoadingOlderMessages = false,
        toolCardCount = 0,
    )
    private var committedScale = 1f
    private var running = false

    private val callback: Choreographer.FrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val previous = lastFrameTimeNanos
            lastFrameTimeNanos = frameTimeNanos
            if (previous != 0L) {
                frameDurationsMs += ((frameTimeNanos - previous) / 1_000_000L).coerceAtLeast(0L)
            }
            choreographer?.postFrameCallback(this)
        }
    }

    fun start(context: ChatPinchFrameBudgetStartContext) {
        cancel()
        visibleItems = context.visibleItems
        totalItems = context.totalItems
        visibleUserMessages = context.visibleContent.userMessages
        visibleAssistantMessages = context.visibleContent.assistantMessages
        visibleToolCards = context.visibleContent.toolCards
        visibleRunBlocks = context.visibleContent.runBlocks
        loadPressure = context.loadPressure
        committedScale = context.committedScale
        frameDurationsMs.clear()
        startedAtMs = System.currentTimeMillis()
        lastFrameTimeNanos = 0L
        running = true
        choreographer = Choreographer.getInstance().also { it.postFrameCallback(callback) }
        emitFrameBudgetStartedTelemetry()
    }

    fun stop(context: ChatPinchFrameBudgetStopContext) {
        if (!running) return
        val elapsedMs = System.currentTimeMillis() - startedAtMs
        val frames = frameDurationsMs.toList()
        cancel()
        if (frames.isEmpty()) {
            emitEmptyFrameBudgetFinishedTelemetry(
                ChatPinchFrameBudgetEmptyFinishedContext(
                    elapsedMs = elapsedMs,
                    scales = context,
                ),
            )
            return
        }
        emitFrameBudgetFinishedTelemetry(
            ChatPinchFrameBudgetFinishedContext(
                frames = frames,
                elapsedMs = elapsedMs,
                scales = context,
            ),
        )
    }

    fun cancel() {
        if (running) {
            choreographer?.removeFrameCallback(callback)
        }
        running = false
        choreographer = null
        lastFrameTimeNanos = 0L
    }

    private fun emitFrameBudgetStartedTelemetry() {
        Telemetry.event(
            "ChatPinch",
            "frameBudget.started",
            *frameBudgetTelemetryPairs(
                ChatPinchFrameBudgetStopContext(
                    committedScale = committedScale,
                    targetScale = committedScale,
                ),
                includeTargetScale = false,
            ),
        )
    }

    private fun emitEmptyFrameBudgetFinishedTelemetry(context: ChatPinchFrameBudgetEmptyFinishedContext) {
        Telemetry.event(
            "ChatPinch",
            "frameBudget.finished",
            "frames" to 0,
            "elapsedMs" to context.elapsedMs,
            *frameBudgetTelemetryPairs(context.scales, includeTargetScale = true),
        )
    }

    private fun emitFrameBudgetFinishedTelemetry(context: ChatPinchFrameBudgetFinishedContext) {
        val sorted = context.frames.sorted()
        val frameBudgetMs = 16L
        val jankFrames = context.frames.count { it > frameBudgetMs }
        val maxMs = context.frames.maxOrNull() ?: 0L
        val avgMs = context.frames.average()
        val p95Index = ((sorted.size - 1) * 95 / 100).coerceIn(0, sorted.lastIndex)
        Telemetry.event(
            "ChatPinch",
            "frameBudget.finished",
            "frames" to context.frames.size,
            "jankFrames" to jankFrames,
            "jankPercent" to ((jankFrames * 100.0) / context.frames.size),
            "avgMs" to avgMs,
            "p95Ms" to sorted[p95Index],
            "maxMs" to maxMs,
            "overBudgetTotalMs" to context.frames.sumOf { (it - frameBudgetMs).coerceAtLeast(0L) },
            "elapsedMs" to context.elapsedMs,
            *frameBudgetTelemetryPairs(context.scales, includeTargetScale = true),
        )
    }

    private fun frameBudgetTelemetryPairs(
        scales: ChatPinchFrameBudgetStopContext,
        includeTargetScale: Boolean,
    ): Array<out Pair<String, Any?>> {
        val pairs = mutableListOf<Pair<String, Any?>>(
            "visibleItems" to visibleItems,
            "totalItems" to totalItems,
            "visibleUserMessages" to visibleUserMessages,
            "visibleAssistantMessages" to visibleAssistantMessages,
            "visibleToolCards" to visibleToolCards,
            "visibleRunBlocks" to visibleRunBlocks,
            "messageCount" to loadPressure.messageCount,
            "renderItemCount" to loadPressure.renderItemCount,
            "isStreaming" to loadPressure.isStreaming,
            "isLoadingMessages" to loadPressure.isLoadingMessages,
            "isLoadingOlderMessages" to loadPressure.isLoadingOlderMessages,
            "isHydrating" to loadPressure.isHydrating,
            "isReconciling" to loadPressure.isReconciling,
            "toolCardCount" to loadPressure.toolCardCount,
            "committedScale" to scales.committedScale,
        )
        if (includeTargetScale) {
            pairs += "targetScale" to scales.targetScale
        }
        return pairs.toTypedArray()
    }
}
