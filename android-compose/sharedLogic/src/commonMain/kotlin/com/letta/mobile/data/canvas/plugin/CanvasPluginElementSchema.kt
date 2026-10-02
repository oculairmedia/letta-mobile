package com.letta.mobile.data.canvas.plugin

import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.CanvasOp
import com.letta.mobile.data.storage.AssetRefs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One envelope rule a plugin element write breaks: where ([path], JSON-pointer style, `/fallback/title`) and why. */
data class CanvasPluginProblem(val path: String, val reason: String)

/** A field of the envelope and how long it may be: chars for a string, serialised bytes for an object. */
data class CanvasPluginLimit(val path: String, val max: Int)

/**
 * The plugin element envelope, the part of a plugin element the host owns whatever the plugin
 * (plan section 4.3, step 1), built in code like [com.letta.mobile.data.canvas.CanvasSceneSchema].
 *
 * It holds what every client relies on to place and draw the element without the plugin: a
 * namespaced type, a sane frame, small flat props, a snapshot that is an asset ref, and a fallback
 * card with a title. What a kind's props mean is the kind's own schema ([PluginKindCatalog]).
 */
object CanvasPluginElementSchema {
    /** `ext:<pluginId>/<kind>`: a dotted lowercase plugin id, then a lowercase kind. */
    val TYPE_PATTERN: Regex = Regex("^ext:[a-z0-9]+(\\.[a-z0-9]+)+/[a-z0-9-]+$")

    val ID = CanvasPluginLimit("/elementId", 128)
    val REF = CanvasPluginLimit("/ref", 512)
    val PROPS = CanvasPluginLimit("/props", 4 * 1024)
    val META = CanvasPluginLimit("/meta", 1024)
    val TITLE = CanvasPluginLimit("/fallback/title", 120)
    val SUBTITLE = CanvasPluginLimit("/fallback/subtitle", 240)
    val ICON = CanvasPluginLimit("/fallback/icon", 32)
    val OPEN_URL = CanvasPluginLimit("/fallback/openUrl", 2048)

    val URL_SCHEMES: List<String> = listOf("http://", "https://", "meridian:")
    val MEDIA_TYPES: Set<String> = setOf("image/png", "image/jpeg", "image/webp", "image/svg+xml")

    /** The fields an entry may carry besides `_`-prefixed bookkeeping. */
    val ENTRY_FIELDS: Set<String> = setOf("id", "type", "v", "frame", "owner", "ref", "props", "snapshot", "fallback", "meta")

    /** Every envelope rule [op] breaks; empty when it may be written. */
    fun check(op: CanvasOp.SetPluginElementOp): List<CanvasPluginProblem> = buildList {
        addAll(identity(op))
        addAll(version(op))
        addAll(frame(op))
        tooLong(REF, op.ref)?.let(::add)
        op.props?.let { addAll(flat(PROPS, it)) }
        op.meta?.let { addAll(flat(META, it)) }
        op.snapshot?.let { addAll(snapshot(it)) }
        op.fallback?.let { addAll(fallback(it)) }
        if (!namesAnything(op)) add(CanvasPluginProblem("/", "sets nothing: name a frame or the element's state"))
    }

    /**
     * Every rule a stored entry breaks: it must decode as a [CanvasPluginElement], carry no field
     * outside the envelope, and keep every rule a write is held to.
     */
    fun checkEntry(entry: JsonObject): List<CanvasPluginProblem> {
        val element = CanvasPluginElements.decode(entry)
            ?: return listOf(CanvasPluginProblem("/", "does not decode as a plugin element (type, fallback.title)"))
        val unknown = entry.keys.filter { !it.startsWith("_") && it !in ENTRY_FIELDS }
            .map { CanvasPluginProblem("/$it", "is not a plugin element field") }
        return unknown + check(element.asWrite())
    }

    /** What a first write must carry for every client to draw the element: a type, a fallback title, and a snapshot or a link. */
    fun firstWriteProblems(op: CanvasOp.SetPluginElementOp): List<CanvasPluginProblem> = listOfNotNull(
        CanvasPluginProblem("/elementType", "is required on the element's first write").takeIf { op.elementType == null },
        CanvasPluginProblem(TITLE.path, "is required on the element's first write").takeIf { op.fallback == null },
        CanvasPluginProblem("/snapshot", "or fallback.openUrl is required on the element's first write")
            .takeIf { op.snapshot == null && op.fallback?.openUrl == null },
    )

    private fun identity(op: CanvasOp.SetPluginElementOp): List<CanvasPluginProblem> = listOfNotNull(
        CanvasPluginProblem(ID.path, "is blank or over ${ID.max} chars").takeIf { op.elementId.isBlank() || op.elementId.length > ID.max },
        op.elementType?.let { type ->
            CanvasPluginProblem("/elementType", "'$type' is not ext:<pluginId>/<kind> (e.g. ext:acme.charts/bar)")
                .takeIf { !TYPE_PATTERN.matches(type) }
        },
    )

    private fun version(op: CanvasOp.SetPluginElementOp): List<CanvasPluginProblem> = listOfNotNull(
        op.v?.let { v -> CanvasPluginProblem("/v", "is $v; versions start at 1").takeIf { v < 1 } },
        CanvasPluginProblem("/v", "is required with elementType").takeIf { op.elementType != null && op.v == null },
        CanvasPluginProblem("/v", "is only given with elementType").takeIf { op.elementType == null && op.v != null },
    )

    private fun frame(op: CanvasOp.SetPluginElementOp): List<CanvasPluginProblem> {
        val frame = op.frame
            ?: return listOfNotNull(CanvasPluginProblem("/owner", "is only given with a frame").takeIf { op.owner != null })
        return frameProblems(frame)
    }

    private fun frameProblems(frame: CanvasDocumentFrame): List<CanvasPluginProblem> {
        val corner = listOf("/frame/x" to frame.x, "/frame/y" to frame.y)
            .filter { (_, value) -> !value.isFinite() }
            .map { (path, _) -> CanvasPluginProblem(path, "is not a finite number") }
        val size = listOf("/frame/width" to frame.width, "/frame/height" to frame.height)
            .filter { (_, value) -> !value.isFinite() || value <= 0f }
            .map { (path, value) -> CanvasPluginProblem(path, "is $value; it must be a finite number over 0") }
        return corner + size
    }

    /** An object of scalars only, at most [limit] bytes serialised. */
    private fun flat(limit: CanvasPluginLimit, value: JsonObject): List<CanvasPluginProblem> {
        val nested = value.filterValues { it !is JsonPrimitive }.keys
            .map { CanvasPluginProblem("${limit.path}/$it", "is not a scalar: ${limit.path} is flat") }
        val bytes = value.toString().encodeToByteArray().size
        return nested + listOfNotNull(CanvasPluginProblem(limit.path, "is $bytes bytes, over ${limit.max}").takeIf { bytes > limit.max })
    }

    private fun snapshot(snapshot: CanvasPluginSnapshot): List<CanvasPluginProblem> {
        val sizes = listOf("/snapshot/width" to snapshot.width, "/snapshot/height" to snapshot.height)
            .filter { (_, value) -> value != null && value <= 0 }
            .map { (path, value) -> CanvasPluginProblem(path, "is $value; it must be over 0") }
        return sizes + listOfNotNull(
            CanvasPluginProblem("/snapshot/assetRef", "is not an asset ref sha256:<64 hex>").takeIf { !AssetRefs.isValid(snapshot.assetRef) },
            snapshot.mediaType?.takeIf { it !in MEDIA_TYPES }
                ?.let { CanvasPluginProblem("/snapshot/mediaType", "'$it' is not one of ${MEDIA_TYPES.joinToString("|")}") },
            CanvasPluginProblem("/snapshot/rev", "is negative").takeIf { (snapshot.rev ?: 0L) < 0L },
        )
    }

    private fun fallback(fallback: CanvasPluginFallback): List<CanvasPluginProblem> = listOfNotNull(
        CanvasPluginProblem(TITLE.path, "must be 1..${TITLE.max} chars").takeIf { fallback.title.isBlank() },
        tooLong(TITLE, fallback.title),
        tooLong(SUBTITLE, fallback.subtitle),
        tooLong(ICON, fallback.icon),
        tooLong(OPEN_URL, fallback.openUrl),
        CanvasPluginProblem(OPEN_URL.path, "is not an http(s) or meridian: link").takeIf { !linksSafely(fallback) },
    )

    private fun linksSafely(fallback: CanvasPluginFallback): Boolean =
        fallback.openUrl?.let { url -> URL_SCHEMES.any { url.startsWith(it, ignoreCase = true) } } ?: true

    private fun tooLong(limit: CanvasPluginLimit, value: String?): CanvasPluginProblem? =
        value?.takeIf { it.length > limit.max }?.let { CanvasPluginProblem(limit.path, "is ${it.length} chars, over ${limit.max}") }

    private fun namesAnything(op: CanvasOp.SetPluginElementOp): Boolean =
        listOf(op.frame, op.elementType, op.ref, op.props, op.snapshot, op.fallback, op.meta).any { it != null }

    /** The element as the write that would put it on the board, so an entry is held to a write's rules. */
    private fun CanvasPluginElement.asWrite() = CanvasOp.SetPluginElementOp(
        opId = "", actorId = "", lamport = 0L, elementId = id,
        elementType = type, v = v, frame = frame, owner = owner, ref = ref,
        props = props, snapshot = snapshot, fallback = fallback, meta = meta,
    )
}
