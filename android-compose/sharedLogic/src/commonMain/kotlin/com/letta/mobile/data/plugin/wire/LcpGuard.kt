package com.letta.mobile.data.plugin.wire

import com.letta.mobile.plugin.api.LcpMethod
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** A guard's verdict on a call's params: let them through (possibly rewritten), or refuse with an error. */
sealed interface LcpAdmission {
    data class Admit(val params: JsonObject) : LcpAdmission

    data class Refuse(val error: JsonRpcError) : LcpAdmission
}

/**
 * The policy hooks at the protocol boundary of an [LcpPeer]: the session state machine, the
 * capability check and the secret scrubber each implement some of them and [LcpGuards.chain] runs
 * them in order. Every hook runs on the peer, so a refusal is the same JSON-RPC error whatever the
 * transport.
 */
interface LcpGuard {
    /** A call the other side sent, before its handler runs. */
    fun incoming(method: LcpMethod, params: JsonObject): LcpAdmission = LcpAdmission.Admit(params)

    /** A call this side is about to send. */
    fun outgoing(method: LcpMethod, params: JsonObject): LcpAdmission = LcpAdmission.Admit(params)

    /** The result the other side answered to this side's [method]; throws [LcpCallException] to refuse it. */
    fun result(method: LcpMethod, result: JsonElement): JsonElement = result

    /** An error the other side answered. */
    fun error(error: JsonRpcError): JsonRpcError = error

    /** A request finished, either way round: the session moves on. */
    fun completed(method: LcpMethod, ok: Boolean) {}
}

object LcpGuards {
    /** Admits everything. */
    val NONE: LcpGuard = object : LcpGuard {}

    /** [guards] in order: the first refusal wins, rewrites pass along. */
    fun chain(vararg guards: LcpGuard): LcpGuard = ChainedGuard(guards.toList())
}

private class ChainedGuard(private val guards: List<LcpGuard>) : LcpGuard {
    override fun incoming(method: LcpMethod, params: JsonObject): LcpAdmission = fold(params) { guard, p -> guard.incoming(method, p) }

    override fun outgoing(method: LcpMethod, params: JsonObject): LcpAdmission = fold(params) { guard, p -> guard.outgoing(method, p) }

    override fun result(method: LcpMethod, result: JsonElement): JsonElement = guards.fold(result) { r, guard -> guard.result(method, r) }

    override fun error(error: JsonRpcError): JsonRpcError = guards.fold(error) { e, guard -> guard.error(e) }

    override fun completed(method: LcpMethod, ok: Boolean) = guards.forEach { it.completed(method, ok) }

    private fun fold(params: JsonObject, step: (LcpGuard, JsonObject) -> LcpAdmission): LcpAdmission {
        var admitted = params
        for (guard in guards) {
            when (val admission = step(guard, admitted)) {
                is LcpAdmission.Admit -> admitted = admission.params
                is LcpAdmission.Refuse -> return admission
            }
        }
        return LcpAdmission.Admit(admitted)
    }
}
