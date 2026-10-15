package com.letta.mobile.data.plugin.wire

import com.letta.mobile.plugin.api.LcpMethod
import com.letta.mobile.plugin.api.LcpDirection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject

/** Where an LCP session is: the same on both sides of the wire. */
enum class LcpSessionState { UNINITIALIZED, INITIALIZED, ACTIVE, STOPPING, CLOSED }

/**
 * The session state machine of LCP wire v1, as a [LcpGuard] on both sides: uninitialized ->
 * initialized (after `plugin.initialize`) -> active (after `plugin.activate`) -> stopping (from
 * `plugin.deactivate`, or when the host starts draining) -> closed. Each state admits a fixed set
 * of methods, in either direction; anything else is refused with [LcpErrorCode.NOT_INITIALIZED]
 * before the handshake and [LcpErrorCode.SESSION_STATE] after it. While stopping, the plugin's own
 * calls are still served, so the actions being drained can finish their emits.
 */
class PluginWireSession : LcpGuard {
    private val current = MutableStateFlow(LcpSessionState.UNINITIALIZED)
    val state: StateFlow<LcpSessionState> = current.asStateFlow()

    override fun incoming(method: LcpMethod, params: JsonObject): LcpAdmission = admit(method, params)

    override fun outgoing(method: LcpMethod, params: JsonObject): LcpAdmission = admit(method, params)

    override fun completed(method: LcpMethod, ok: Boolean) {
        val next = after(method, ok) ?: return
        advance(next)
    }

    /** Stop taking new work (the host's drain before `plugin.deactivate`). */
    fun stop() = advance(LcpSessionState.STOPPING)

    fun close() = advance(LcpSessionState.CLOSED)

    /** States only move forward: a late answer never reopens a stopping or closed session. */
    private fun advance(next: LcpSessionState) = current.update { if (next.ordinal > it.ordinal) next else it }

    private fun admit(method: LcpMethod, params: JsonObject): LcpAdmission {
        val state = current.value
        if (method !in admitted.getValue(state)) return LcpAdmission.Refuse(refusal(state, method))
        if (method == LcpMethod.DEACTIVATE) stop()
        return LcpAdmission.Admit(params)
    }

    private fun refusal(state: LcpSessionState, method: LcpMethod): JsonRpcError = if (state == LcpSessionState.UNINITIALIZED) {
        JsonRpcError(LcpErrorCode.NOT_INITIALIZED, "${method.wire} before plugin.initialize")
    } else {
        JsonRpcError(LcpErrorCode.SESSION_STATE, "${method.wire} refused while the session is ${state.name.lowercase()}")
    }

    private companion object {
        val PLUGIN_CALLS: Set<LcpMethod> = LcpMethod.entries.filter { it.direction == LcpDirection.PLUGIN_TO_HOST }.toSet()
        val SETTLED: Set<LcpMethod> = setOf(LcpMethod.ACTIVATE, LcpMethod.HEALTH, LcpMethod.DEACTIVATE, LcpMethod.SETTINGS_CHANGED)

        val admitted: Map<LcpSessionState, Set<LcpMethod>> = mapOf(
            LcpSessionState.UNINITIALIZED to setOf(LcpMethod.INITIALIZE),
            LcpSessionState.INITIALIZED to SETTLED + PLUGIN_CALLS,
            LcpSessionState.ACTIVE to LcpMethod.entries.toSet() - LcpMethod.INITIALIZE,
            LcpSessionState.STOPPING to setOf(LcpMethod.DEACTIVATE, LcpMethod.HEALTH) + PLUGIN_CALLS,
            LcpSessionState.CLOSED to emptySet(),
        )

        /** The state a finished request moves to, or null when it moves nowhere. */
        fun after(method: LcpMethod, ok: Boolean): LcpSessionState? = when (method) {
            LcpMethod.INITIALIZE -> LcpSessionState.INITIALIZED.takeIf { ok }
            LcpMethod.ACTIVATE -> LcpSessionState.ACTIVE.takeIf { ok }
            LcpMethod.DEACTIVATE -> LcpSessionState.CLOSED
            else -> null
        }
    }
}
