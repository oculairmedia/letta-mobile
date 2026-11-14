package com.letta.mobile.data.controller.node.iroh.secrets

import com.letta.mobile.data.secrets.host.SecretServiceResult
import com.letta.mobile.data.secrets.request.SecretRequestRefusal
import com.letta.mobile.data.secrets.request.SecretServiceError
import com.letta.mobile.data.secrets.request.SecretRefusalText
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.attr
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import com.letta.mobile.data.serialization.LettaJson
import kotlinx.serialization.json.JsonElement

/**
 * One route's answer, before a transport encodes it: a result object, or a refusal with a fixed
 * code. Neither ever carries a value, a value's length, a path, a message or an exception.
 */
internal sealed interface SecretRouteAnswer {
    /** The fixed telemetry outcome: `ok` or the refusal text. */
    val outcome: String

    /** The call succeeded with [result]. */
    class Ok(
        /** The wire result. */
        val result: JsonElement,
    ) : SecretRouteAnswer {
        override val outcome: String get() = OK
    }

    /** The call was refused with [refusal]. */
    class Refused(
        /** The fixed refusal. */
        val refusal: SecretRequestRefusal,
    ) : SecretRouteAnswer {
        /** The admin RPC `error` text, e.g. `not_pending` or `invalid_request:purpose_blank`. */
        val text: String get() = SecretRefusalText.of(refusal)

        override val outcome: String get() = text
    }

    /** Shared answers. */
    companion object {
        private const val OK = "ok"

        /** A refusal with [error] and no problem. */
        fun refused(error: SecretServiceError): Refused = Refused(SecretRequestRefusal(error))
    }
}

/** How results are written: optional fields omitted when absent, never `null` (contract, Methods). */
internal val secretWireJson: Json = LettaJson.wire

/** [value] as wire JSON. */
internal fun <T> wireOf(serializer: KSerializer<T>, value: T): JsonElement = secretWireJson.encodeToJsonElement(serializer, value)

/** A service answer as a route answer: [encode] the value, or carry the refusal's code and problem. */
internal inline fun <T> SecretServiceResult<T>.answer(encode: (T) -> JsonElement): SecretRouteAnswer = when (this) {
    is SecretServiceResult.Ok -> SecretRouteAnswer.Ok(encode(value))
    is SecretServiceResult.Refused -> SecretRouteAnswer.Refused(SecretRequestRefusal(error, problem))
}

/** Reports the outcome: the method, the side and the fixed outcome code; never an id, a name, a purpose or a value. */
internal fun SecretRouteAnswer.report(route: SecretRequestRoute, via: String): SecretRouteAnswer {
    Telemetry.event(
        SECRET_ROUTE_TAG,
        "route.outcome",
        "method" attr route.method,
        "side" attr route.side.name.lowercase(),
        "via" attr via,
        "outcome" attr outcome,
    )
    return this
}

/** Telemetry tag of every secret request route event. */
internal const val SECRET_ROUTE_TAG = "SecretRequestRoute"
