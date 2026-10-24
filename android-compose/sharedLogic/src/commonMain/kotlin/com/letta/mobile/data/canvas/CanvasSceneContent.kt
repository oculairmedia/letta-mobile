package com.letta.mobile.data.canvas

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** The scene root's table of element tombstones (see [CanvasOpProjector]). */
private const val ELEMENT_TOMBSTONES = "_removed"

/** How every provenance key the projector writes ends: `_lamport`, `_bgActorId`, `_bgPatternOpId`... */
private val PROVENANCE_SUFFIXES = listOf("lamport", "actorId", "opId")

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
