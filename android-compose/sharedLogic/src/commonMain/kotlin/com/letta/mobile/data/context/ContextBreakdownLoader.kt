package com.letta.mobile.data.context

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ContextWindowOverview
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.transport.iroh.AdminRpcErrors
import com.letta.mobile.util.runCatchingCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** letta-mobile-cyh28: one breakdown request: which conversation, matched to which total. */
data class ContextBreakdownRequest(
    val agentId: AgentId,
    val conversationId: ConversationId?,
    /** The latest exact streamed total; null when there is none or it is a post-compaction estimate. */
    val reportedTotal: Int?,
)

/** Why a breakdown is not shown; the meter falls back to the streamed total either way. */
enum class ContextBreakdownUnavailable {
    /** The host has no local-backend store (`capability_unavailable`), or no `agent.context` at all. */
    NotSupported,

    /** The request failed for another reason (network, decode). */
    Failed,
}

sealed interface ContextBreakdownState {
    data object Idle : ContextBreakdownState

    data class Loading(val request: ContextBreakdownRequest) : ContextBreakdownState

    data class Loaded(val request: ContextBreakdownRequest, val overview: ContextWindowOverview) : ContextBreakdownState

    data class Unavailable(
        val request: ContextBreakdownRequest,
        val reason: ContextBreakdownUnavailable,
        val detail: String?,
    ) : ContextBreakdownState
}

/**
 * letta-mobile-cyh28: fetches the estimated breakdown on demand, never on a timer.
 *
 * Callers [load] when the context sheet opens and again after a turn settles or a compaction
 * finishes; a repeat request for the same conversation and total is served from the last answer.
 * The meter never waits on it: [ContextMeter.of] draws the streamed total until a breakdown lands.
 */
class ContextBreakdownLoader(
    private val fetch: suspend (ContextBreakdownRequest) -> ContextWindowOverview,
) {
    constructor(repository: IAgentRepository) : this({ request ->
        repository.getContextBreakdown(request.agentId, request.conversationId, request.reportedTotal)
    })

    private val mutableState = MutableStateFlow<ContextBreakdownState>(ContextBreakdownState.Idle)
    val state: StateFlow<ContextBreakdownState> = mutableState.asStateFlow()

    private val lock = Mutex()

    suspend fun load(request: ContextBreakdownRequest, force: Boolean = false): ContextBreakdownState = lock.withLock {
        if (!force) answeredFor(request)?.let { return@withLock it }
        mutableState.value = ContextBreakdownState.Loading(request)
        val next = runCatchingCancellable { fetch(request) }.fold(
            onSuccess = { ContextBreakdownState.Loaded(request, it) },
            onFailure = { ContextBreakdownState.Unavailable(request, classify(it), it.message) },
        )
        mutableState.value = next
        next
    }

    private fun answeredFor(request: ContextBreakdownRequest): ContextBreakdownState.Loaded? =
        (mutableState.value as? ContextBreakdownState.Loaded)?.takeIf { it.request == request }

    /** Forget the last answer, e.g. when the focused conversation changes. */
    fun clear() {
        mutableState.value = ContextBreakdownState.Idle
    }

    private fun classify(error: Throwable): ContextBreakdownUnavailable {
        val message = error.message.orEmpty()
        val unsupported = message.contains(CAPABILITY_UNAVAILABLE, ignoreCase = true) || AdminRpcErrors.isUnknownMethod(message)
        return if (unsupported) {
            ContextBreakdownUnavailable.NotSupported
        } else {
            ContextBreakdownUnavailable.Failed
        }
    }

    private companion object {
        const val CAPABILITY_UNAVAILABLE = "capability_unavailable"
    }
}

/** The breakdown a [ContextBreakdownState] holds for [request], when it holds one. */
fun ContextBreakdownState.overviewFor(request: ContextBreakdownRequest?): ContextWindowOverview? =
    (this as? ContextBreakdownState.Loaded)?.takeIf {
        request == null || (it.request.agentId == request.agentId && it.request.conversationId == request.conversationId)
    }?.overview
