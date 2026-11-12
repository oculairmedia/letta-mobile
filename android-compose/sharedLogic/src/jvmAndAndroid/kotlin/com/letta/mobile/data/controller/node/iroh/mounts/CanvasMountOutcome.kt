package com.letta.mobile.data.controller.node.iroh.mounts

import com.letta.mobile.data.canvas.mount.CanvasMountFailure
import com.letta.mobile.data.canvas.mount.CanvasMountResult
import com.letta.mobile.data.canvas.mount.wire.CanvasMountWire
import com.letta.mobile.data.controller.node.iroh.adminError
import com.letta.mobile.data.hoststate.HostDirectoryDurability
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.attr
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Where a resolved canvas came from. */
internal enum class CanvasMountSource(val wire: String) {
    BINDING(CanvasMountWire.SOURCE_BINDING),
    DEFAULT(CanvasMountWire.SOURCE_DEFAULT),
}

/** Fixed `unavailable` reasons. */
internal enum class CanvasMountUnavailable(val wire: String) {
    STORAGE(CanvasMountWire.REASON_STORAGE),
    AUTHORITY(CanvasMountWire.REASON_AUTHORITY),
    TIMEOUT_BEFORE_COMMIT(CanvasMountWire.REASON_TIMEOUT_BEFORE_COMMIT),
    IDENTITY(CanvasMountWire.REASON_IDENTITY),
}

/** Owner decision O1: what this host can promise about an acknowledged record. */
internal enum class CanvasMountDurability(val wire: String) {
    /** Contents, atomic replacement and directory entries are confirmed. */
    DURABLE(CanvasMountWire.DURABLE),

    /** Windows: contents forced and atomically replaced; the directory entry is unconfirmed (finding F3). */
    ENTRY_UNCONFIRMED(CanvasMountWire.ENTRY_UNCONFIRMED),
    ;

    companion object {
        fun ofHost(): CanvasMountDurability = if (HostDirectoryDurability.supported()) DURABLE else ENTRY_UNCONFIRMED
    }
}

/**
 * One route's wire outcome. Canvas-bearing outcomes carry only a canvas the caller was authorized to see or had
 * just been authorized to store; nothing carries a path, message, exception or another record's id.
 */
internal sealed interface CanvasMountOutcome {
    /** The wire status, also the telemetry outcome. */
    val status: String

    /** The `success: true` result; refusals throw instead. */
    fun body(durability: CanvasMountDurability): JsonElement = buildJsonObject { put(CanvasMountWire.STATUS, status) }

    /** Fixed, id-free telemetry detail beyond the route and status. */
    fun detail(): CanvasMountDetail = CanvasMountDetail()

    data class Found(val canvas: CanvasId, val source: CanvasMountSource) : CanvasMountOutcome {
        override val status: String get() = CanvasMountWire.FOUND

        override fun body(durability: CanvasMountDurability): JsonElement = canvasBody(status, canvas, durability) {
            put(CanvasMountWire.SOURCE, source.wire)
        }
    }

    data class Bound(val canvas: CanvasId, val won: Boolean) : CanvasMountOutcome {
        override val status: String get() = CanvasMountWire.BOUND

        override fun body(durability: CanvasMountDurability): JsonElement = canvasBody(status, canvas, durability) {
            put(CanvasMountWire.WON, won)
        }
    }

    data class Replaced(val canvas: CanvasId) : CanvasMountOutcome {
        override val status: String get() = CanvasMountWire.REPLACED

        override fun body(durability: CanvasMountDurability): JsonElement = canvasBody(status, canvas, durability) {}
    }

    data class DefaultSet(val canvas: CanvasId) : CanvasMountOutcome {
        override val status: String get() = CanvasMountWire.DEFAULT_SET

        override fun body(durability: CanvasMountDurability): JsonElement = canvasBody(status, canvas, durability) {}
    }

    data object None : CanvasMountOutcome {
        override val status: String get() = CanvasMountWire.NONE
    }

    data object OutcomeUnknown : CanvasMountOutcome {
        override val status: String get() = CanvasMountWire.OUTCOME_UNKNOWN
    }

    data class Unavailable(val reason: CanvasMountUnavailable, val failure: CanvasMountFailure? = null) : CanvasMountOutcome {
        override val status: String get() = CanvasMountWire.UNAVAILABLE

        override fun body(durability: CanvasMountDurability): JsonElement = buildJsonObject {
            put(CanvasMountWire.STATUS, status)
            put(CanvasMountWire.REASON, reason.wire)
        }

        override fun detail(): CanvasMountDetail = CanvasMountDetail(reason = reason.wire, failure = failure?.name)
    }

    /** Refused with [CanvasMountWire.DENIED], identical for every denial. */
    data object Denied : CanvasMountOutcome {
        override val status: String get() = "denied"

        override fun body(durability: CanvasMountDurability): JsonElement = adminError(CanvasMountWire.DENIED)
    }

    /** Refused with a fixed code built from the route's own field names. */
    data class InvalidInput(val field: CanvasMountField, val problem: CanvasMountInputProblem) : CanvasMountOutcome, CanvasMountParsed {
        override val status: String get() = "invalid_input"

        override fun body(durability: CanvasMountDurability): JsonElement =
            adminError("${CanvasMountWire.INVALID_INPUT_PREFIX}:${field.wire}:${problem.wire}")

        override fun detail(): CanvasMountDetail = CanvasMountDetail(field = field.wire, problem = problem.wire)
    }
}

/** Reports the outcome (fixed event name, enum values only) and answers it on the wire. */
internal fun CanvasMountOutcome.reply(route: CanvasMountRoute, durability: CanvasMountDurability): JsonElement {
    val detail = detail()
    Telemetry.event(
        CANVAS_MOUNT_TAG,
        "route.outcome",
        "route" attr route.method,
        "outcome" attr status,
        "reason" attr detail.reason,
        "failure" attr detail.failure,
        "field" attr detail.field,
        "problem" attr detail.problem,
    )
    return body(durability)
}

/** Telemetry detail: fixed wire codes and enum names only, never ids, paths or messages; null when absent. */
internal data class CanvasMountDetail(
    val reason: String? = null,
    val failure: String? = null,
    val field: String? = null,
    val problem: String? = null,
)

/** Maps a facade result; [proposed] is the caller's own canvas for a bind, which decides `won`. */
internal fun CanvasMountResult.toOutcome(source: CanvasMountSource, proposed: CanvasId?): CanvasMountOutcome =
    canvasOutcome(source, proposed) ?: plainOutcome()

private fun CanvasMountResult.canvasOutcome(source: CanvasMountSource, proposed: CanvasId?): CanvasMountOutcome? = when (this) {
    is CanvasMountResult.Found -> CanvasMountOutcome.Found(canvas, source)
    is CanvasMountResult.Bound -> CanvasMountOutcome.Bound(canvas, won = canvas == proposed)
    is CanvasMountResult.Replaced -> CanvasMountOutcome.Replaced(canvas)
    is CanvasMountResult.DefaultSet -> CanvasMountOutcome.DefaultSet(canvas)
    else -> null
}

private fun CanvasMountResult.plainOutcome(): CanvasMountOutcome = when (this) {
    CanvasMountResult.None -> CanvasMountOutcome.None
    CanvasMountResult.Denied -> CanvasMountOutcome.Denied
    is CanvasMountResult.StorageFailure -> CanvasMountOutcome.Unavailable(CanvasMountUnavailable.STORAGE, reason)
    // The handler validated every id first, so the facade's own bound check cannot fail here.
    CanvasMountResult.InvalidInput -> CanvasMountOutcome.InvalidInput(CanvasMountField.PARAMS, CanvasMountInputProblem.MALFORMED)
    else -> CanvasMountOutcome.OutcomeUnknown
}

private inline fun canvasBody(
    status: String,
    canvas: CanvasId,
    durability: CanvasMountDurability,
    extra: JsonObjectBuilder.() -> Unit,
): JsonObject = buildJsonObject {
    put(CanvasMountWire.STATUS, status)
    put(CanvasMountWire.CANVAS_ID, canvas.value)
    extra()
    put(CanvasMountWire.DURABILITY, durability.wire)
}

/** Telemetry tag of every canvas mount route event. */
internal const val CANVAS_MOUNT_TAG = "CanvasMount"
