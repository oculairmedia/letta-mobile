package com.letta.mobile.data.plugin.wire

import com.letta.mobile.plugin.api.LcpMethod
import com.letta.mobile.data.plugin.PluginCapability
import com.letta.mobile.data.plugin.SecretScrubber
import com.letta.mobile.plugin.api.PluginEmit
import com.letta.mobile.plugin.api.SnapshotSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Capability enforcement at the protocol boundary (plan section 3.3), on the host: a plugin's call
 * to the host is refused with [LcpErrorCode.CAPABILITY_REFUSED] (and `data.capability`) unless the
 * plugin holds what it needs: `host.putAsset.*` needs `assets:write`, `host.readElements`
 * `canvas:read`, and `host.emit` `canvas:place` for placements, updates and removals and
 * `assets:write` for images and inline snapshot bytes. [granted] is what the owner consented to.
 */
class LcpCapabilityGuard(private val granted: Set<PluginCapability>) : LcpGuard {
    override fun incoming(method: LcpMethod, params: JsonObject): LcpAdmission {
        val missing = required(method, params).firstOrNull { it !in granted } ?: return LcpAdmission.Admit(params)
        return LcpAdmission.Refuse(refusal(method, missing))
    }

    private fun refusal(method: LcpMethod, missing: PluginCapability): JsonRpcError = JsonRpcError(
        code = LcpErrorCode.CAPABILITY_REFUSED,
        message = "${method.wire} needs the capability ${missing.wire}",
        data = buildJsonObject { put("capability", missing.wire) },
    )

    companion object {
        /**
         * The capability each plugin-to-host method needs as a whole (plan section 3.3); `host.emit`
         * is checked by what it carries, `host.log` needs none. The host's policy over the one
         * method registry in `:plugin-api`.
         */
        val byMethod: Map<LcpMethod, PluginCapability> = mapOf(
            LcpMethod.PUT_ASSET_BEGIN to PluginCapability.ASSETS_WRITE,
            LcpMethod.PUT_ASSET_CHUNK to PluginCapability.ASSETS_WRITE,
            LcpMethod.PUT_ASSET_END to PluginCapability.ASSETS_WRITE,
            LcpMethod.READ_ELEMENTS to PluginCapability.CANVAS_READ,
        )

        /** The capabilities a call of [method] with [params] needs; params that do not decode need none here (their handler refuses them). */
        fun required(method: LcpMethod, params: JsonObject): Set<PluginCapability> = when (method) {
            LcpMethod.EMIT -> emitNeeds(params)
            else -> setOfNotNull(byMethod[method])
        }

        private fun emitNeeds(params: JsonObject): Set<PluginCapability> {
            val emit = runCatching { LcpCalls.EMIT.decodeParams(params) }.getOrNull() ?: return emptySet()
            return buildSet {
                if (emit.touchesElements()) add(PluginCapability.CANVAS_PLACE)
                if (emit.carriesBytes()) add(PluginCapability.ASSETS_WRITE)
            }
        }

        private fun PluginEmit.touchesElements(): Boolean = place.isNotEmpty() || update.isNotEmpty() || remove.isNotEmpty()

        private fun PluginEmit.carriesBytes(): Boolean =
            placeImages.isNotEmpty() || place.any { it.snapshot is SnapshotSource.Bytes } || update.any { it.snapshot is SnapshotSource.Bytes }
    }
}

/**
 * The secret discipline of the wire (plan section 3.4), on the host. Secrets never travel in the
 * protocol: they reach a `process` plugin through env and a `service` plugin through connection
 * headers, so a host message holding one is refused before it is sent. Everything the plugin sends
 * passes the [scrubber], failing closed: a `host.log` keeps its shape with each leaking string
 * replaced by [SecretScrubber.REFUSED]; any other call, or a result, that holds a secret is refused
 * whole with [LcpErrorCode.SECRET_REFUSED]; an error's message is scrubbed and its data dropped.
 */
class LcpSecretGuard(private val scrubber: SecretScrubber) : LcpGuard {
    override fun incoming(method: LcpMethod, params: JsonObject): LcpAdmission = when {
        method == LcpMethod.LOG -> LcpAdmission.Admit(scrub(params) as JsonObject)
        leaks(params) -> LcpAdmission.Refuse(JsonRpcError(LcpErrorCode.SECRET_REFUSED, "${method.wire} held a secret and was refused"))
        else -> LcpAdmission.Admit(params)
    }

    override fun outgoing(method: LcpMethod, params: JsonObject): LcpAdmission = if (leaks(params)) {
        LcpAdmission.Refuse(JsonRpcError(LcpErrorCode.SECRET_REFUSED, "secrets never cross the wire; ${method.wire} was not sent"))
    } else {
        LcpAdmission.Admit(params)
    }

    override fun result(method: LcpMethod, result: JsonElement): JsonElement {
        if (leaks(result)) throw LcpCallException(LcpErrorCode.SECRET_REFUSED, "the result of ${method.wire} held a secret and was refused")
        return result
    }

    override fun error(error: JsonRpcError): JsonRpcError = JsonRpcError(
        code = error.code,
        message = scrubber.scrub(error.message),
        data = error.data?.takeUnless(::leaks),
    )

    /** Whether any key or string anywhere in [element] holds a secret. */
    fun leaks(element: JsonElement): Boolean = when (element) {
        is JsonObject -> element.any { (key, value) -> scrubber.leaks(key) || leaks(value) }
        is JsonArray -> element.any(::leaks)
        is JsonPrimitive -> element.isString && scrubber.leaks(element.content)
    }

    /** [element] with every leaking string replaced whole (a leaking key drops its entry). */
    fun scrub(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.filterKeys { !scrubber.leaks(it) }.mapValues { (_, value) -> scrub(value) })
        is JsonArray -> JsonArray(element.map(::scrub))
        is JsonPrimitive -> if (element.isString && scrubber.leaks(element.content)) JsonPrimitive(SecretScrubber.REFUSED) else element
    }
}
