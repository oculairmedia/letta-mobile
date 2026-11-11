package com.letta.mobile.data.canvas.library

import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One destination's hold in its original backend, retained by the host-provided [scope].
 * Calls are serialized on the host window dispatcher. Retirement is terminal, including failed pages.
 */
class CanvasPageHold(private val owner: CanvasBoardOwner, private val scope: CoroutineScope) : CanvasPage {
    private val shown = MutableStateFlow<CanvasPageState>(CanvasPageState.Loading)
    private var request: Triple<CanvasId?, ConversationId?, AgentId?>? = null
    private var bound: CanvasBoardSessions? = null
    private var held: CanvasSession? = null
    private var opening: Job? = null
    private var disposeRetirement: (() -> Unit)? = null
    override val state = shown.asStateFlow()

    override fun open(canvasId: CanvasId?, conversationId: ConversationId?, agentId: AgentId?) {
        if (shown.value == CanvasPageState.Closed) return
        if (request != null) {
            retry()
            return
        }
        val pageId = canvasId ?: if (conversationId == null) CanvasId.generate() else null
        request = Triple(pageId, conversationId, agentId)
        val boards = owner.boards.value
        bound = boards
        disposeRetirement = owner.onRetire(boards, ::close)
        acquire()
    }

    override fun retry() {
        if (shown.value is CanvasPageState.Failed) acquire()
    }

    override fun stateFor(boards: CanvasBoardSessions, observed: CanvasPageState): CanvasPageState =
        when {
            shown.value == CanvasPageState.Closed -> CanvasPageState.Closed
            bound != null && bound !== boards -> CanvasPageState.Closed
            else -> observed
        }

    override fun close() {
        if (shown.value == CanvasPageState.Closed) return
        shown.value = CanvasPageState.Closed
        disposeRetirement?.invoke()
        disposeRetirement = null
        opening?.cancel()
        opening = null
        held?.let { bound?.release(it) }
        held = null
        bound = null
        request = null
    }

    private fun acquire() {
        if (opening != null || shown.value == CanvasPageState.Closed) return
        val boards = bound ?: return
        val target = request ?: return
        if (owner.boards.value !== boards) {
            close()
            return
        }
        shown.value = CanvasPageState.Loading
        opening = scope.launch(start = CoroutineStart.LAZY) {
            var pending: CanvasSession? = null
            try {
                val result = runCatchingCancellable {
                    val session = boards.acquirePage(target.first, target.second, target.third)
                    pending = session
                    session
                }
                currentCoroutineContext().ensureActive()
                result.fold(onSuccess = { pending = receive(boards, it) }, onFailure = ::fail)
            } finally {
                pending?.let(boards::release)
                opening = null
            }
        }
        opening?.start()
    }

    private fun receive(boards: CanvasBoardSessions, session: CanvasSession): CanvasSession? {
        if (shown.value == CanvasPageState.Closed || owner.boards.value !== boards) {
            close()
            return session
        }
        held = session
        shown.value = CanvasPageState.Ready(session)
        return null
    }

    private fun fail(error: Throwable) {
        if (error !is Exception) throw error
        shown.value = CanvasPageState.Failed(error.message ?: "Canvas storage is unavailable")
        Telemetry.error("CanvasPage", "acquireFailed", error)
    }
}
