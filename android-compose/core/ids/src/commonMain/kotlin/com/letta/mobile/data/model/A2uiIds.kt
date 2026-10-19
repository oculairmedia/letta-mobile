package com.letta.mobile.data.model

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * The id of one A2UI surface: the `surfaceId` an agent names in
 * `createSurface`/`updateComponents`/`updateDataModel`/`deleteSurface`. The A2UI wire messages and
 * the surface map keep it as a bare string ([value]); the render budget, the limit telemetry, the
 * renderer's local-state scope and the surface lookups take it typed. Not blank-checked: the
 * protocol model defaults an absent `surfaceId` to `""`, and lookups treat blank as "no surface".
 */
@JvmInline
@Serializable
value class A2uiSurfaceId(val value: String) {
    override fun toString(): String = value
}

/**
 * The id of one component in an A2UI surface's registry: the `id` of an
 * entry in `updateComponents.components`, which `root`, `child` and `children` refer to. The wire
 * and `A2uiComponent.id` keep it as a bare string ([value]); the render budget and the renderer's
 * render-slot and local-state keys take it typed. Not blank-checked: agent output is untrusted and
 * the renderer must degrade, not throw, on an odd id.
 */
@JvmInline
@Serializable
value class A2uiComponentId(val value: String) {
    override fun toString(): String = value
}
