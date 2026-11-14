package com.letta.mobile.data.controller.node.iroh.secrets

import com.letta.mobile.data.secrets.request.SecretRequestMethods
import com.letta.mobile.data.secrets.wire.SecretRequestRouteWire
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Which principal a route serves. */
internal enum class SecretRouteSide {
    /** The requesting agent, as the host runtime stamped its tool call. */
    AGENT,

    /** A person on a paired device holding `admin.full`. */
    HUMAN,
}

/** The six routes: wire method, side and exact params. */
internal enum class SecretRequestRoute(val method: String, val side: SecretRouteSide, val fields: Set<String>) {
    REQUEST(
        SecretRequestMethods.REQUEST,
        SecretRouteSide.AGENT,
        setOf(SecretRequestRouteWire.PURPOSE, SecretRequestRouteWire.VAULT_ITEM_NAME, SecretRequestRouteWire.FIELD_NAME),
    ),
    STATUS(SecretRequestMethods.STATUS, SecretRouteSide.AGENT, setOf(SecretRequestRouteWire.REQUEST_ID)),
    LIST(SecretRequestMethods.LIST, SecretRouteSide.HUMAN, emptySet()),
    GET(SecretRequestMethods.GET, SecretRouteSide.HUMAN, setOf(SecretRequestRouteWire.REQUEST_ID)),
    SUBMIT(SecretRequestMethods.SUBMIT, SecretRouteSide.HUMAN, setOf(SecretRequestRouteWire.REQUEST_ID, SecretRequestRouteWire.VALUE)),
    CANCEL(SecretRequestMethods.CANCEL, SecretRouteSide.HUMAN, setOf(SecretRequestRouteWire.REQUEST_ID)),
    ;

    /**
     * Strict params: an object (or none, for a route without fields) holding exactly this route's
     * fields, each a JSON string. Unknown fields are refused, so a spoofed agent, principal or target
     * field can never be read; nothing about the params is echoed. Answers null when refused.
     *
     * Each value is the JSON layer's own string ([JsonPrimitive.content]); no copy is made here.
     */
    fun parse(params: JsonObject?): SecretRouteInput? {
        val given = params ?: JsonObject(emptyMap())
        if (given.keys != fields) return null
        val values = given.mapValues { (_, element) -> (element as? JsonPrimitive)?.takeIf { it.isString }?.content }
        if (values.values.any { it == null }) return null
        return SecretRouteInput(values.mapValues { (_, value) -> checkNotNull(value) })
    }

    /** Lookup by wire method. */
    companion object {
        /** The route serving [method], or null. */
        fun of(method: String): SecretRequestRoute? = entries.firstOrNull { it.method == method }
    }
}

/** The validated string fields of one call; an accessor is valid only on a route that defines its field. */
internal class SecretRouteInput(private val values: Map<String, String>) {
    /** A field's text; the route's [SecretRequestRoute.fields] guarantee it is present. */
    operator fun get(field: String): String = values.getValue(field)

    /** Field names only: a submission's value never prints. */
    override fun toString(): String = "SecretRouteInput(${values.keys})"
}
