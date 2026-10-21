package com.letta.mobile.data.context

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ContextWindowOverview
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.transport.iroh.AdminRpcErrors
import com.letta.mobile.util.runCatchingCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One breakdown request: which conversation, matched to which total. */
data class ContextBreakdownRequest(
    val agentId: AgentId,
    val conversationId: ConversationId?,
    /** The latest exact streamed total; null when there is none or it is a post-compaction estimate. */
    val reportedTotal: Int?,
) {
    /** The conversation this breakdown describes, whatever total it was matched to. */
    val key: ContextBreakdownKey get() = ContextBreakdownKey(agentId, conversationId)
}

/** One conversation's place in the breakdown cache. */
data class ContextBreakdownKey(val agentId: AgentId, val conversationId: ConversationId?)

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
 * The single owner of the context breakdown a session shows: the composer meter and the agent
 * context card both read [breakdowns], so they never disagree.
 *
 * Nothing polls. [follow] fetches the focused conversation's breakdown when it is opened and again
 * once each turn settles (its total changes), dropping a stale fetch when the focus moves; the
 * card's sheet [load]s too and after a compaction. A repeat request for the same conversation and
 * total is served from the last answer, and each conversation's last breakdown stays in
 * [breakdowns], so a revisit draws it at once while the refresh runs.
 */
class ContextBreakdownLoader(
    private val fetch: suspend (ContextBreakdownRequest) -> ContextWindowOverview,
) {
    constructor(repository: IAgentRepository) : this({ request ->
        repository.getContextBreakdown(request.agentId, request.conversationId, request.reportedTotal)
    })

    private val mutableState = MutableStateFlow<ContextBreakdownState>(ContextBreakdownState.Idle)
    val state: StateFlow<ContextBreakdownState> = mutableState.asStateFlow()

    private val cache = MutableStateFlow<Map<ContextBreakdownKey, ContextWindowOverview>>(emptyMap())

    /** Each conversation's last loaded breakdown. */
    val breakdowns: StateFlow<Map<ContextBreakdownKey, ContextWindowOverview>> = cache.asStateFlow()

    private val lock = Mutex()

    suspend fun load(request: ContextBreakdownRequest, force: Boolean = false): ContextBreakdownState = lock.withLock {
        if (!force) answeredFor(request)?.let { return@withLock it }
        mutableState.value = ContextBreakdownState.Loading(request)
        val next = runCatchingCancellable { fetch(request) }.fold(
            onSuccess = { overview ->
                cache.update { it + (request.key to overview) }
                ContextBreakdownState.Loaded(request, overview)
            },
            onFailure = { ContextBreakdownState.Unavailable(request, classify(it), it.message) },
        )
        mutableState.value = next
        next
    }

    /**
     * Keeps the focused conversation's breakdown loaded until cancelled: each new request (a
     * conversation opened, or a settled turn's new total) is fetched in the background, and one
     * still in flight when the focus moves on is cancelled. Null is no focus.
     */
    suspend fun follow(requests: Flow<ContextBreakdownRequest?>) {
        requests.distinctUntilChanged().collectLatest { request -> request?.let { load(it) } }
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
