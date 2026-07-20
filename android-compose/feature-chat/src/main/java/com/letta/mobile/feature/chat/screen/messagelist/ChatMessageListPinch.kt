package com.letta.mobile.feature.chat.screen.messagelist

import android.view.Choreographer
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.util.Telemetry

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

internal fun Collection<ChatRenderItem>.pinchVisibleContentSummary(): ChatPinchVisibleContentSummary {
    var userMessages = 0
    var assistantMessages = 0
    var toolCards = 0
    var runBlocks = 0

    fun countMessage(message: UiMessage) {
        when {
            message.role == "user" -> userMessages++
            message.role == "assistant" -> assistantMessages++
        }
        if (message.role == "tool" || !message.toolCalls.isNullOrEmpty() || message.generatedUi != null) {
            toolCards++
        }
    }

    for (item in this) {
        when (item) {
            is ChatRenderItem.Single -> countMessage(item.message)
            is ChatRenderItem.RunBlock -> {
                runBlocks++
                item.messages.forEach { (message, _) -> countMessage(message) }
            }
            is ChatRenderItem.SkillEnvelopeChip -> {
                // Skill envelope chips don't count towards pinch summary stats
            }
        }
    }

    return ChatPinchVisibleContentSummary(
        userMessages = userMessages,
        assistantMessages = assistantMessages,
        toolCards = toolCards,
        runBlocks = runBlocks,
    )
}

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

    fun start(
        visibleItems: Int,
        totalItems: Int,
        visibleUserMessages: Int,
        visibleAssistantMessages: Int,
        visibleToolCards: Int,
        visibleRunBlocks: Int,
        loadPressure: ChatLoadPressureSummary,
        committedScale: Float,
    ) {
        cancel()
        this.visibleItems = visibleItems
        this.totalItems = totalItems
        this.visibleUserMessages = visibleUserMessages
        this.visibleAssistantMessages = visibleAssistantMessages
        this.visibleToolCards = visibleToolCards
        this.visibleRunBlocks = visibleRunBlocks
        this.loadPressure = loadPressure
        this.committedScale = committedScale
        frameDurationsMs.clear()
        startedAtMs = System.currentTimeMillis()
        lastFrameTimeNanos = 0L
        running = true
        choreographer = Choreographer.getInstance().also { it.postFrameCallback(callback) }
        Telemetry.event(
            "ChatPinch",
            "frameBudget.started",
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
            "committedScale" to committedScale,
        )
    }

    fun stop(committedScale: Float, targetScale: Float) {
        if (!running) return
        val elapsedMs = System.currentTimeMillis() - startedAtMs
        val frames = frameDurationsMs.toList()
        cancel()
        if (frames.isEmpty()) {
            Telemetry.event(
                "ChatPinch",
                "frameBudget.finished",
                "frames" to 0,
                "elapsedMs" to elapsedMs,
                "committedScale" to committedScale,
                "targetScale" to targetScale,
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
            )
            return
        }
        val sorted = frames.sorted()
        val frameBudgetMs = 16L
        val jankFrames = frames.count { it > frameBudgetMs }
        val maxMs = frames.maxOrNull() ?: 0L
        val avgMs = frames.average()
        val p95Index = ((sorted.size - 1) * 95 / 100).coerceIn(0, sorted.lastIndex)
        Telemetry.event(
            "ChatPinch",
            "frameBudget.finished",
            "frames" to frames.size,
            "jankFrames" to jankFrames,
            "jankPercent" to ((jankFrames * 100.0) / frames.size),
            "avgMs" to avgMs,
            "p95Ms" to sorted[p95Index],
            "maxMs" to maxMs,
            "overBudgetTotalMs" to frames.sumOf { (it - frameBudgetMs).coerceAtLeast(0L) },
            "elapsedMs" to elapsedMs,
            "committedScale" to committedScale,
            "targetScale" to targetScale,
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
}
