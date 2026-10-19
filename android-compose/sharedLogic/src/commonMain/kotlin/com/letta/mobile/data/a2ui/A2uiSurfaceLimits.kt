package com.letta.mobile.data.a2ui

import com.letta.mobile.data.model.A2uiComponentId
import com.letta.mobile.data.model.A2uiSurfaceId
import com.letta.mobile.util.Telemetry

/**
 * Budgets that keep one server frame from freezing or exhausting the UI thread.
 *
 * A surface is untrusted agent output. Without caps a 10k-child column, or a diamond DAG
 * (`children: [b, b]` at every level) that expands to 2^depth rendered instances, composes on the
 * main thread until the app stalls or runs out of memory. The surface manager enforces the
 * structural cap when an update lands; the renderer enforces the rest per composition pass
 * through [A2uiRenderBudget], as a backstop for whatever a valid-sized registry can still expand to.
 */
object A2uiSurfaceLimits {
    /** Distinct components one surface may hold. The richest real surfaces (forms, schedule editors) use < 200; 10x headroom. */
    const val MAX_COMPONENTS_PER_SURFACE = 2_000

    /** Children one container renders before a truncation placeholder. A chat bubble is not a data grid; longer lists page server-side. */
    const val MAX_CHILDREN_PER_CONTAINER = 200

    /** Rendered component instances (DAG references counted each time) in one surface pass. ~2.5x the component cap. */
    const val MAX_RENDERED_NODES_PER_SURFACE = 5_000

    /** Nesting depth; unchanged from the renderer's historical cycle guard. */
    const val MAX_RENDER_DEPTH = 32

    /** Instances of one component (in one list-item scope) before further references render as placeholders; this flattens diamond DAGs. */
    const val MAX_INSTANCES_PER_COMPONENT = 16

    /** Characters a single text binding renders; longer values are cut (64 KiB is far past any readable bubble). */
    const val MAX_TEXT_LENGTH_PER_NODE = 64 * 1024

    internal const val TELEMETRY_TAG = "A2uiLimits"
}

/** Why a surface update was refused; the renderer shows a "surface too large" placeholder for it. */
sealed interface A2uiSurfaceLimitViolation {
    data class TooManyComponents(val count: Int, val limit: Int) : A2uiSurfaceLimitViolation
}

/** [this] cut to [A2uiSurfaceLimits.MAX_TEXT_LENGTH_PER_NODE] characters. */
fun String.clampToA2uiTextLimit(): String =
    if (length <= A2uiSurfaceLimits.MAX_TEXT_LENGTH_PER_NODE) this else take(A2uiSurfaceLimits.MAX_TEXT_LENGTH_PER_NODE)

/** The violation merging [incoming] into [existing] would cause, or null when the result stays within budget. */
internal fun checkComponentBudget(
    existing: Map<String, A2uiComponent>,
    incoming: Map<String, A2uiComponent>,
): A2uiSurfaceLimitViolation? {
    val count = existing.size + incoming.keys.count { it !in existing }
    return if (count > A2uiSurfaceLimits.MAX_COMPONENTS_PER_SURFACE) {
        A2uiSurfaceLimitViolation.TooManyComponents(count, A2uiSurfaceLimits.MAX_COMPONENTS_PER_SURFACE)
    } else {
        null
    }
}

internal fun logSurfaceRejected(surfaceId: A2uiSurfaceId, violation: A2uiSurfaceLimitViolation) {
    when (violation) {
        is A2uiSurfaceLimitViolation.TooManyComponents -> Telemetry.event(
            A2uiSurfaceLimits.TELEMETRY_TAG, "surface.rejected",
            "surfaceId" to surfaceId.value,
            "reason" to "tooManyComponents",
            "count" to violation.count,
            "limit" to violation.limit,
            level = Telemetry.Level.WARN,
        )
    }
}

/** What a render slot may do: render, or show a placeholder because the pass or the component ran out. */
enum class A2uiRenderClaim { Granted, DeniedBudget, DeniedRepeat }

/**
 * Per-surface-pass render counter. Each rendered component instance claims a slot before it
 * composes; a denied slot renders a placeholder and does not descend. Slots are released when the
 * instance leaves composition, so the counts track what is on screen and recomposition of an
 * instance that already holds a slot never re-claims.
 *
 * Bound: claims are granted in composition (depth-first) order, so once the pass is exhausted only
 * the containers still open on the depth-first stack (at most [maxDepth]) get further child slots,
 * each capped at [A2uiSurfaceLimits.MAX_CHILDREN_PER_CONTAINER]; denied placeholders are bounded too.
 */
class A2uiRenderBudget(
    private val maxNodes: Int = A2uiSurfaceLimits.MAX_RENDERED_NODES_PER_SURFACE,
    private val maxInstancesPerComponent: Int = A2uiSurfaceLimits.MAX_INSTANCES_PER_COMPONENT,
    private val onDenied: (componentId: A2uiComponentId, claim: A2uiRenderClaim) -> Unit = { _, _ -> },
) {
    private val instances = HashMap<String, Int>()
    private var deniedReported = false

    /** Instances currently holding a slot. */
    var rendered: Int = 0
        private set

    /** Claims a slot for an instance of [componentId] rendered under list-item [scopeKey]. */
    fun claim(componentId: A2uiComponentId, scopeKey: String = ""): A2uiRenderClaim {
        val key = instanceKey(componentId, scopeKey)
        val claim = when {
            rendered >= maxNodes -> A2uiRenderClaim.DeniedBudget
            (instances[key] ?: 0) >= maxInstancesPerComponent -> A2uiRenderClaim.DeniedRepeat
            else -> A2uiRenderClaim.Granted
        }
        if (claim == A2uiRenderClaim.Granted) {
            rendered++
            instances[key] = (instances[key] ?: 0) + 1
        } else if (!deniedReported) {
            deniedReported = true
            onDenied(componentId, claim)
        }
        return claim
    }

    /** Gives back a slot [claim] granted for [componentId] under [scopeKey]. */
    fun release(componentId: A2uiComponentId, scopeKey: String = "") {
        val key = instanceKey(componentId, scopeKey)
        val held = instances[key] ?: return
        if (held <= 1) instances.remove(key) else instances[key] = held - 1
        rendered = (rendered - 1).coerceAtLeast(0)
    }

    private fun instanceKey(componentId: A2uiComponentId, scopeKey: String) = "$scopeKey\u0000${componentId.value}"

    companion object {
        /** A budget that reports its first denial for [surfaceId] through Telemetry. */
        fun forSurface(surfaceId: A2uiSurfaceId): A2uiRenderBudget = A2uiRenderBudget { componentId, claim ->
            Telemetry.event(
                A2uiSurfaceLimits.TELEMETRY_TAG, "render.truncated",
                "surfaceId" to surfaceId.value,
                "componentId" to componentId.value,
                "reason" to claim.name,
                level = Telemetry.Level.WARN,
            )
        }
    }
}
