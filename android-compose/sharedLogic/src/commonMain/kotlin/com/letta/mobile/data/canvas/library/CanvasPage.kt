package com.letta.mobile.data.canvas.library

import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import kotlinx.coroutines.flow.StateFlow

/** The retained page's observable acquisition state, including recoverable failure and terminal closure. */
sealed interface CanvasPageState {
    /** An acquisition is pending. */
    data object Loading : CanvasPageState
    /** The page owns this session. */
    data class Ready(
        /** Session acquired in this page's original owner. */
        val session: CanvasSession,
    ) : CanvasPageState
    /** A storage failure that may be retried in this same backend. */
    data class Failed(
        /** Recoverable storage diagnostic; hosts may present their localized failure surface. */
        val message: String,
    ) : CanvasPageState
    /** This page has ended; its identifiers must never be opened in a replacement backend. */
    data object Closed : CanvasPageState
}

/** Shared page lifecycle adapted by platform ViewModels without implementing acquisition policy. */
interface CanvasPage {
    /** Current acquisition state. */
    val state: StateFlow<CanvasPageState>
    /** Binds this destination once; an untitled destination keeps one generated board identity. */
    fun open(canvasId: CanvasId?, conversationId: ConversationId?, agentId: AgentId?)
    /** Retries a failed acquisition in the original backend only. */
    fun retry()
    /** Validates an observed state against the root's current board services. */
    fun stateFor(boards: CanvasBoardSessions, observed: CanvasPageState): CanvasPageState
    /** Gives back the destination's hold and ends its lifecycle. */
    fun close()
}
