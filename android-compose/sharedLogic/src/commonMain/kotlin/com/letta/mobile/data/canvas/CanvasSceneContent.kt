package com.letta.mobile.data.canvas

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** The scene root's table of element tombstones (see [CanvasOpProjector]). */
private const val ELEMENT_TOMBSTONES = "_removed"

/** How every provenance key the projector writes ends: `_lamport`, `_bgActorId`, `_bgPatternOpId`... */
private const val LAMPORT = "lamport"

private val PROVENANCE_SUFFIXES = listOf(LAMPORT, "actorId", "opId")

/**
 * Whether scenes [a] and [b] hold the same board, differing at most in provenance: who last wrote
 * each value and when (every `_...lamport`, `_...actorId` and `_...opId` key, at any depth), and
 * the tombstones of elements that are gone in both. An op that loses last-writer-wins, re-sends the
 * value already there or removes what was already removed changes only these.
 *
 * The provenance is what keeps replicas converging, so such a scene is still kept; it is just not a
 * change anyone made to the board (see [CanvasSession]'s revisions).
 */
internal fun sameSceneContent(a: String, b: String): Boolean {
    if (a == b) return true
    // A blank scene is a board never written; the first write to it is a change, whatever it holds.
    val left = a.takeIf { it.isNotBlank() }?.let(CanvasOpProjector::parseSceneOrNull) ?: return false
    val right = b.takeIf { it.isNotBlank() }?.let(CanvasOpProjector::parseSceneOrNull) ?: return false
    return withoutProvenance(JsonObject(left - ELEMENT_TOMBSTONES)) == withoutProvenance(JsonObject(right - ELEMENT_TOMBSTONES))
}

private fun withoutProvenance(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.filterKeys { !isProvenanceKey(it) }.mapValues { (_, value) -> withoutProvenance(value) })
    is JsonArray -> JsonArray(element.map(::withoutProvenance))
    else -> element
}

private fun isProvenanceKey(key: String): Boolean =
    key.startsWith("_") && PROVENANCE_SUFFIXES.any { key.endsWith(it, ignoreCase = true) }

/**
 * Whether [stored] holds a newer writer for any value than [held] does: some element, tombstone,
 * document, binding or background whose provenance moved on, or that [held] has no provenance for.
 *
 * A change that leaves the board as it was is no revision, yet it moves the provenance of what it
 * wrote. Two copies at one revision can therefore differ, and only value by value can tell which is
 * ahead: the scene-wide highest Lamport cannot, as another value may already hold it.
 */
internal fun hasNewerProvenance(stored: CanvasDocument, held: CanvasDocument): Boolean {
    val newer = CanvasOpProjector.parseSceneOrNull(stored.sceneJson)?.let(::stampsOf) ?: return false
    val older = CanvasOpProjector.parseSceneOrNull(held.sceneJson)?.let(::stampsOf).orEmpty()
    return newer.any { (path, stamp) -> older[path]?.let { isNewer(stamp, it) } ?: true }
}

/** Writers' stamps are (lamport, actor, op id), ordered as the projector orders writers. */
private fun isNewer(a: Triple<Long, String, String>, b: Triple<Long, String, String>): Boolean =
    CanvasOpOrder.compare(a.first, a.second, a.third, b.first, b.second, b.third) > 0

/** Every provenance stamp in [root], keyed by where it sits (array entries by their id). */
private fun stampsOf(root: JsonObject): Map<String, Triple<Long, String, String>> = buildMap { collectStamps(root, "", this) }

private fun collectStamps(node: JsonElement, path: String, into: MutableMap<String, Triple<Long, String, String>>) {
    when (node) {
        is JsonObject -> node.forEach { (key, value) ->
            stampAt(node, key)?.let { into["$path/$key"] = it }
            collectStamps(value, "$path/$key", into)
        }
        is JsonArray -> node.forEachIndexed { index, child -> collectStamps(child, "$path/${entryId(child) ?: index}", into) }
        else -> Unit
    }
}

/** The stamp whose Lamport is [key] (`_lamport`, `_bgLamport`...), with the actor and op id beside it. */
private fun stampAt(node: JsonObject, key: String): Triple<Long, String, String>? {
    if (!key.startsWith("_") || !key.endsWith(LAMPORT, ignoreCase = true)) return null
    val lamport = (node[key] as? JsonPrimitive)?.longOrNull ?: return null
    val prefix = key.dropLast(LAMPORT.length)
    return Triple(lamport, node.textAt(prefix + "actorId"), node.textAt(prefix + "opId"))
}

/** The text under [name], matched ignoring case (`_actorId` beside `_lamport`, `_bgActorId` beside `_bgLamport`). */
private fun JsonObject.textAt(name: String): String =
    entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }?.let { (it.value as? JsonPrimitive)?.content }.orEmpty()

private fun entryId(entry: JsonElement): String? = ((entry as? JsonObject)?.get("id") as? JsonPrimitive)?.content
