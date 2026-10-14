package com.letta.mobile.data.plugin.host

import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.CanvasGeometryOwner
import com.letta.mobile.data.canvas.CanvasOp
import com.letta.mobile.data.canvas.HostCanvasBackend
import com.letta.mobile.data.canvas.HostCanvasCaller
import com.letta.mobile.data.canvas.HostCanvasEntry
import com.letta.mobile.data.canvas.HostCanvasPublish
import com.letta.mobile.data.canvas.plugin.CanvasPluginElement
import com.letta.mobile.data.canvas.plugin.CanvasPluginElementSchema
import com.letta.mobile.data.canvas.plugin.CanvasPluginFallback
import com.letta.mobile.data.canvas.plugin.CanvasPluginSnapshot
import com.letta.mobile.data.canvas.plugin.PluginKindCatalog
import com.letta.mobile.plugin.api.ElementFallback
import com.letta.mobile.plugin.api.EmitReceipt
import com.letta.mobile.plugin.api.EmitRefusal
import com.letta.mobile.plugin.api.PlaceElement
import com.letta.mobile.plugin.api.PluginEmit
import com.letta.mobile.plugin.api.SnapshotSource
import com.letta.mobile.plugin.api.UpdateElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The boards a plugin may place on. The host runtime (letta-mobile-cdgpr.6) binds it to the board
 * of the call that invoked the plugin; [requestedCanvasId] is what the emit named, if anything.
 * Null refuses the emit. Being handed a board is the plugin's write grant on it: a board's ACL lists
 * agents and users, never plugins.
 */
fun interface PluginCanvasAccess {
    suspend fun boardFor(requestedCanvasId: String?): HostCanvasEntry?
}

/**
 * Turns one plugin's `host.emit` into canvas ops (plan: trust minimum, letta-mobile-cdgpr.5).
 *
 * Ownership is by element id prefix, not a registry: the host mints every id the plugin places as
 * `p-<pluginId>-<suffix>` (plugin ids have no `-`, so no plugin's prefix is another's), and an
 * update or removal may only name ids with the plugin's own prefix. Nothing to persist, and it
 * survives restarts. Kinds are the plugin's own: `<kind>` or `ext:<pluginId>/<kind>`.
 *
 * Refusals are typed ([PluginRefusal]) and counted ([PluginRefusals]):
 *  - an ownership breach (a foreign kind or element id) refuses the whole emit, `-32006`, nothing written;
 *  - an entry the host cannot take (a kind/version the [kinds] catalog has no schema for, props over
 *    [maxPropsBytes], inline bytes, `placeImages`) is left out and named in the receipt
 *    (`<reason>: <detail>`), as the wire contract says, and the rest is placed;
 *  - the rest goes through [HostCanvasBackend.publish] as one atomic batch, written as
 *    `plugin:<pluginId>`; that is where the one canvas validator ([com.letta.mobile.data.canvas.CanvasBatchValidator])
 *    holds props to the kind's schema, so the backend must be built with the same [kinds]. A
 *    batch it refuses answers `-32602` and writes nothing.
 */
@OptIn(ExperimentalUuidApi::class)
class PlacementCompiler(
    private val pluginId: String,
    private val kinds: PluginKindCatalog,
    private val backend: HostCanvasBackend,
    private val access: PluginCanvasAccess,
    private val refusals: PluginRefusals,
    private val maxPropsBytes: Int = CanvasPluginElementSchema.PROPS.max,
    private val newIdSuffix: () -> String = { Uuid.random().toHexString().take(ID_SUFFIX_LENGTH) },
) {
    val actorId: String = "$ACTOR_PREFIX$pluginId"
    private val idPrefix = "p-$pluginId-"
    private val typePrefix = "${CanvasPluginElement.TYPE_PREFIX}$pluginId/"

    private class Compiled(val ops: List<CanvasOp>, val placed: List<String>, val refused: List<EmitRefusal>)

    fun owns(elementId: String): Boolean = elementId.startsWith(idPrefix) && elementId.length > idPrefix.length

    suspend fun emit(emit: PluginEmit): EmitReceipt {
        checkOwnership(emit)
        val compiled = compile(emit)
        if (compiled.ops.isEmpty()) return EmitReceipt(refused = compiled.refused)
        val board = access.boardFor(boardOf(emit)) ?: throw refuse(PluginRefusal.NO_CANVAS, "no board is open to $pluginId")
        val granted = board.copy(acl = board.acl.copy(writerAgentIds = board.acl.writerAgentIds + actorId))
        return when (val published = backend.publish(HostCanvasCaller(actorId), granted, compiled.ops, atomic = true)) {
            is HostCanvasPublish.Published -> EmitReceipt(compiled.placed, compiled.refused)
            is HostCanvasPublish.Denied -> throw refuse(PluginRefusal.NO_CANVAS, published.reason)
            is HostCanvasPublish.Invalid -> throw refuse(PluginRefusal.INVALID_BATCH, published.reason)
        }
    }

    /** The plugin's element type for [kind], or null when [kind] is another plugin's. */
    fun typeOf(kind: String): String? = when {
        kind.startsWith(CanvasPluginElement.TYPE_PREFIX) -> kind.takeIf { it.startsWith(typePrefix) }
        '/' in kind || ':' in kind -> null
        else -> typePrefix + kind
    }

    private fun checkOwnership(emit: PluginEmit) {
        emit.place.forEachIndexed { index, place ->
            if (typeOf(place.kind) == null) throw refuse(PluginRefusal.FOREIGN_KIND, "'${place.kind}' is not a kind of $pluginId", index)
        }
        val named = emit.update.map { it.elementId } + emit.remove
        named.forEachIndexed { offset, elementId ->
            if (!owns(elementId)) throw refuse(PluginRefusal.FOREIGN_ELEMENT, "'$elementId' is not an element of $pluginId", emit.place.size + offset)
        }
    }

    private fun boardOf(emit: PluginEmit): String? {
        val named = emit.place.mapNotNull { it.canvasId }.distinct()
        if (named.size > 1) throw refuse(PluginRefusal.MIXED_CANVAS, "an emit places on one board; it named $named")
        return named.firstOrNull()
    }

    private fun compile(emit: PluginEmit): Compiled {
        val ops = mutableListOf<CanvasOp>()
        val placed = mutableListOf<String>()
        val refused = mutableListOf<EmitRefusal>()
        fun refuseEntry(index: Int, refusal: PluginRefusal, detail: String) {
            refusals.record(pluginId, refusal)
            refused += EmitRefusal(index, "${refusal.wire}: $detail")
        }
        emit.place.forEachIndexed { index, place ->
            val problem = placeProblem(place)
            if (problem != null) return@forEachIndexed refuseEntry(index, problem.first, problem.second)
            val elementId = idPrefix + newIdSuffix()
            ops += place(elementId, place)
            placed += elementId
        }
        emit.update.forEachIndexed { offset, entry ->
            val problem = propsProblem(entry.props) ?: snapshotProblem(entry.snapshot)
            if (problem != null) return@forEachIndexed refuseEntry(emit.place.size + offset, problem.first, problem.second)
            ops += update(entry)
        }
        emit.remove.forEach { ops += CanvasOp.RemovePluginElementOp(opId = "", actorId = actorId, lamport = 0L, elementId = it) }
        val imagesFrom = emit.place.size + emit.update.size + emit.remove.size
        emit.placeImages.indices.forEach { refuseEntry(imagesFrom + it, PluginRefusal.PLACE_IMAGES, "placeImages is not served in contract v1") }
        return Compiled(ops, placed, refused)
    }

    private fun placeProblem(place: PlaceElement): Pair<PluginRefusal, String>? {
        val type = typeOf(place.kind) ?: return PluginRefusal.FOREIGN_KIND to place.kind
        if (kinds.schemaFor(type, place.v) == null) return PluginRefusal.UNKNOWN_KIND to "$type v${place.v} is not installed"
        return propsProblem(place.props) ?: snapshotProblem(place.snapshot)
    }

    private fun propsProblem(props: JsonObject?): Pair<PluginRefusal, String>? {
        val bytes = props?.toString()?.encodeToByteArray()?.size ?: return null
        return (PluginRefusal.PROPS_TOO_LARGE to "props are $bytes bytes, over $maxPropsBytes").takeIf { bytes > maxPropsBytes }
    }

    private fun snapshotProblem(snapshot: SnapshotSource?): Pair<PluginRefusal, String>? =
        (PluginRefusal.INLINE_BYTES to "store the snapshot with host.putAsset and place its ref").takeIf { snapshot is SnapshotSource.Bytes }

    private fun place(elementId: String, place: PlaceElement): CanvasOp.SetPluginElementOp {
        val frame = place.frame?.let { CanvasDocumentFrame(it.x, it.y, it.width, it.height) }
        return setOp(elementId).copy(
            elementType = typeOf(place.kind),
            v = place.v,
            frame = frame,
            owner = frame?.let { CanvasGeometryOwner.EXPLICIT },
            props = place.props,
            fallback = fallbackOf(place.fallback),
            snapshot = place.snapshot?.let(::snapshotOf),
            ref = place.ref,
            meta = JsonObject(mapOf(META_PLUGIN to JsonPrimitive(pluginId))),
        )
    }

    private fun update(update: UpdateElement): CanvasOp.SetPluginElementOp = setOp(update.elementId).copy(
        props = update.props,
        fallback = update.fallback?.let(::fallbackOf),
        snapshot = update.snapshot?.let(::snapshotOf),
        ref = update.ref,
    )

    private fun setOp(elementId: String) = CanvasOp.SetPluginElementOp(opId = "", actorId = actorId, lamport = 0L, elementId = elementId)

    private fun fallbackOf(fallback: ElementFallback) = CanvasPluginFallback(fallback.title, fallback.subtitle, fallback.icon, fallback.openUrl)

    private fun snapshotOf(source: SnapshotSource): CanvasPluginSnapshot? = (source as? SnapshotSource.Asset)?.let { CanvasPluginSnapshot(it.ref) }

    private fun refuse(refusal: PluginRefusal, message: String, index: Int? = null) = refusals.refuse(pluginId, refusal, message, index)

    companion object {
        /** Every write a plugin makes is attributed to `plugin:<pluginId>`. */
        const val ACTOR_PREFIX: String = "plugin:"

        /** The provenance key in a placed element's `meta`. */
        const val META_PLUGIN: String = "plugin"

        private const val ID_SUFFIX_LENGTH = 16
    }
}
