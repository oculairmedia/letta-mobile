package com.letta.mobile.data.node.core

import com.letta.mobile.core.ffi.AgentRuntimeSpec
import com.letta.mobile.core.ffi.HostException
import com.letta.mobile.core.ffi.QuiesceOutcome
import com.letta.mobile.core.ffi.RuntimeHost
import com.letta.mobile.core.ffi.RuntimeInfo
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.runtime.host.LocalAppServerHost
import com.letta.mobile.data.runtime.host.LocalAppServerLease
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Turn-level control of the running letta-code App Server, which the core asks for around moves
 * and checkpoints. The App Server host itself only starts and stops the process.
 */
interface RuntimeTurnControl {
    /** Waits up to [timeout] for [agent]'s turn to finish; true when idle. */
    suspend fun quiesce(agent: AgentId, timeout: Duration): Boolean

    /** Lets letta-code commit [agent]'s memory and flush its files. */
    suspend fun checkpoint(agent: AgentId)

    /** Aborts [agent]'s in-flight turn. */
    suspend fun abortTurn(agent: AgentId)

    /** No turn tracking: always idle, checkpoints and aborts do nothing. */
    object Untracked : RuntimeTurnControl {
        override suspend fun quiesce(agent: AgentId, timeout: Duration): Boolean = true

        override suspend fun checkpoint(agent: AgentId) = Unit

        override suspend fun abortTurn(agent: AgentId) = Unit
    }
}

/**
 * The core's [RuntimeHost] over the shared [LocalAppServerHost]: starting an agent's runtime
 * acquires an App Server lease for it (one App Server serves every agent the node holds), stopping
 * it closes the lease, and turn-level calls go to [turns]. [runtimeInfo] answers node.info's
 * runtime fields from what the platform already knows.
 */
class LocalAppServerRuntimeHost(
    private val appServer: LocalAppServerHost,
    private val runtimeInfo: () -> RuntimeInfo,
    private val turns: RuntimeTurnControl = RuntimeTurnControl.Untracked,
) : RuntimeHost {
    private val leases = ConcurrentHashMap<String, LocalAppServerLease>()

    override fun info(): RuntimeInfo = runtimeInfo()

    override suspend fun start(spec: AgentRuntimeSpec): String = hostCall {
        val lease = appServer.acquire()
        leases.put(spec.agentId, lease)?.close()
        lease.serverUrl
    }

    override suspend fun stop(agent: String) {
        leases.remove(agent)?.close()
    }

    override suspend fun quiesce(agent: String, timeoutMs: ULong): QuiesceOutcome = hostCall {
        val idle = turns.quiesce(AgentId(agent), timeoutMs.toLong().coerceAtLeast(0).milliseconds)
        if (idle) QuiesceOutcome.IDLE else QuiesceOutcome.BUSY
    }

    override suspend fun checkpoint(agent: String) = hostCall { turns.checkpoint(AgentId(agent)) }

    override suspend fun abortTurn(agent: String) = hostCall { turns.abortTurn(AgentId(agent)) }

    override fun endpoint(): String? = leases.values.firstOrNull()?.serverUrl

    /** Runs [block], reporting a platform failure to the core as [HostException.Failed]. */
    private suspend fun <T> hostCall(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("SwallowedException") e: IllegalStateException) {
        throw HostException.Failed(e.message ?: e.toString())
    }
}
