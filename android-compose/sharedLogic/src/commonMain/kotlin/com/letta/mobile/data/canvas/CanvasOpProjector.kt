package com.letta.mobile.data.canvas

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Projects a sequence of [CanvasOp]s onto a DrawBox scene with last-write-wins resolution, such
 * that two peers given the same ops in different arrival orders reach the same scene.
 *
 * Convergence is the whole point of the op log, so every decision is made from the op's own
 * [CanvasOp.lamport] and [CanvasOp.actorId] rather than from when the op happened to arrive.
 * Three pieces of bookkeeping ride along in the scene under `_`-prefixed keys, which
 * [stripMetadataForDrawBox] removes before DrawBox ever sees them:
 *
 *  - each element carries the `_lamport`/`_actorId` that last wrote it;
 *  - the background carries its own, because it is scene-level state, not an element;
 *  - removals leave a tombstone. Without one, a remove that wins simply deletes the element, and a
 *    concurrent older update arriving afterwards finds nothing to lose against and *resurrects* it
 *    — so the peer that saw remove-then-update kept the element and the peer that saw
 *    update-then-remove did not, forever (letta-mobile, review B1).
 */
object CanvasOpProjector {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = false
    }

    private const val DEFAULT_BG_COLOR = "#ffffffff"
    private const val LAMPORT = "_lamport"
    private const val ACTOR = "_actorId"
    private const val TOMBSTONES = "_removed"
    private const val BG_LAMPORT = "_bgLamport"
    private const val BG_ACTOR = "_bgActorId"

    /**
     * How many tombstones a scene keeps. They cannot grow without bound, and the ones that matter
     * are the newest: an op old enough to lose to an evicted tombstone is older than the oldest
     * removal still on record, which in a live session means it long since arrived or was dropped.
     */
    private const val MAX_TOMBSTONES = 512

    /**
     * Serializes a scene in a fixed shape: root keys sorted, elements ordered by their explicit
     * [zIndex] then id, element keys sorted with `id` first.
     *
     * Converging on the same *state* is not enough, because the scene is compared as a string
     * further up - `applyLocalScene` skips a diff when the JSON is unchanged, the workspace
     * re-imports when `doc.sceneJson` differs from what it last exported, and the multiplayer test
     * asserts two sessions hold equal `sceneJson`. Two peers that applied the same ops in different
     * orders would otherwise hold equal scenes written in different key and element orders, and
     * every one of those comparisons would report a change that is not there.
     */
    private fun canonicalScene(root: Map<String, kotlinx.serialization.json.JsonElement>): String {
        val elements = runCatching { root["elements"]?.jsonArray }.getOrNull().orEmpty()
            .sortedWith(
                compareBy<kotlinx.serialization.json.JsonElement>({ zIndexOf(it) }, { idOf(it).orEmpty() }),
            )
            .map { canonicalElement(it) }
        return json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                root.keys.filter { it != "elements" }.sorted().forEach { key -> put(key, root.getValue(key)) }
                put("elements", JsonArray(elements))
            },
        )
    }

    private fun canonicalElement(element: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement {
        val obj = runCatching { element.jsonObject }.getOrNull() ?: return element
        return buildJsonObject {
            obj["id"]?.let { put("id", it) }
            obj.keys.filter { it != "id" }.sorted().forEach { key -> put(key, obj.getValue(key)) }
        }
    }

    private fun zIndexOf(element: kotlinx.serialization.json.JsonElement): Long =
        runCatching { element.jsonObject["zIndex"]?.jsonPrimitive?.long }.getOrNull() ?: 0L

    /** A write with [lamport]/[actor] beats one already recorded at [atLamport]/[atActor]. */
    private fun wins(lamport: Long, actor: String, atLamport: Long?, atActor: String): Boolean =
        atLamport == null || lamport > atLamport || (lamport == atLamport && actor >= atActor)

    /**
     * Projects [ops] onto [baseSceneJson]. A blank base starts from an empty scene.
     */
    fun project(baseSceneJson: String, ops: List<CanvasOp>): String {
        var currentJson = baseSceneJson
        for (op in ops) {
            currentJson = projectSingle(currentJson, op)
        }
        return currentJson
    }

    private fun projectSingle(sceneJson: String, op: CanvasOp): String = when (op) {
        is CanvasOp.ReplaceSceneOp -> replaceScene(op)
        is CanvasOp.SetBackgroundOp -> setBackground(sceneJson, op)
        is CanvasOp.AddElementOp -> upsertElementWithLww(sceneJson, op, op.elementId, op.elementJson)
        is CanvasOp.UpdateElementOp -> upsertElementWithLww(sceneJson, op, op.elementId, op.elementJson)
        is CanvasOp.RemoveElementOp -> removeElementWithLww(sceneJson, op)
        is CanvasOp.BatchOp -> project(sceneJson, op.ops)
    }

    /**
     * A replace is authoritative over everything before it, so its elements are stamped with its
     * own lamport and actor. Otherwise they would arrive with no provenance and the next
     * out-of-order element op — however old — would win against them.
     */
    private fun replaceScene(op: CanvasOp.ReplaceSceneOp): String {
        val incoming = if (op.sceneJson.isNotBlank()) parseScene(op.sceneJson) else parseEmptyScene()
        val stamped = incoming["elements"]?.jsonArray?.map { element ->
            val obj = runCatching { element.jsonObject }.getOrNull() ?: return@map element
            val id = runCatching { obj["id"]?.jsonPrimitive?.content }.getOrNull().orEmpty()
            parseElementWithMetadata(id, json.encodeToString(JsonObject.serializer(), obj), op.lamport, op.actorId)
        } ?: emptyList()
        return canonicalScene(
            buildMap {
                incoming.forEach { (key, value) -> if (key != "elements") put(key, value) }
                put("elements", JsonArray(stamped))
                put(BG_LAMPORT, JsonPrimitive(op.lamport))
                put(BG_ACTOR, JsonPrimitive(op.actorId))
            },
        )
    }

    private fun setBackground(sceneJson: String, op: CanvasOp.SetBackgroundOp): String {
        val parsed = parseScene(sceneJson)
        val atLamport = runCatching { parsed[BG_LAMPORT]?.jsonPrimitive?.long }.getOrNull()
        val atActor = runCatching { parsed[BG_ACTOR]?.jsonPrimitive?.content }.getOrNull().orEmpty()
        // The background is scene-level state with its own history; comparing it against arrival
        // order alone let two peers settle on different colours (review B2).
        if (!wins(op.lamport, op.actorId, atLamport, atActor)) return sceneJson
        return canonicalScene(
            buildMap {
                parsed.forEach { (key, value) ->
                    if (key != "bgColor" && key != BG_LAMPORT && key != BG_ACTOR) put(key, value)
                }
                put("bgColor", JsonPrimitive(op.colorHex))
                put(BG_LAMPORT, JsonPrimitive(op.lamport))
                put(BG_ACTOR, JsonPrimitive(op.actorId))
            },
        )
    }

    private fun upsertElementWithLww(
        sceneJson: String,
        op: CanvasOp,
        elementId: String,
        elementJson: String,
    ): String {
        val parsed = parseScene(sceneJson)
        val tombstones = tombstonesOf(parsed)
        // A removal the element never came back from still counts, even though the element is gone.
        tombstones[elementId]?.let { grave ->
            if (!wins(op.lamport, op.actorId, grave.lamport, grave.actorId)) return sceneJson
        }

        val elements = parsed["elements"]?.jsonArray?.toMutableList() ?: mutableListOf()
        val existingIndex = elements.indexOfFirst { idOf(it) == elementId }
        if (existingIndex >= 0) {
            val existing = elements[existingIndex].jsonObject
            val atLamport = runCatching { existing[LAMPORT]?.jsonPrimitive?.long }.getOrNull()
            val atActor = runCatching { existing[ACTOR]?.jsonPrimitive?.content }.getOrNull().orEmpty()
            if (!wins(op.lamport, op.actorId, atLamport, atActor)) return sceneJson
        }

        val newElement = parseElementWithMetadata(elementId, elementJson, op.lamport, op.actorId)
        if (existingIndex >= 0) elements[existingIndex] = newElement else elements.add(newElement)
        // The write won, so any tombstone for this id is now history.
        return writeScene(parsed, elements, tombstones - elementId)
    }

    private fun removeElementWithLww(sceneJson: String, op: CanvasOp.RemoveElementOp): String {
        val parsed = parseScene(sceneJson)
        val tombstones = tombstonesOf(parsed)
        tombstones[op.elementId]?.let { grave ->
            if (!wins(op.lamport, op.actorId, grave.lamport, grave.actorId)) return sceneJson
        }

        val elements = parsed["elements"]?.jsonArray?.toMutableList() ?: mutableListOf()
        val existingIndex = elements.indexOfFirst { idOf(it) == op.elementId }
        if (existingIndex >= 0) {
            val existing = elements[existingIndex].jsonObject
            val atLamport = runCatching { existing[LAMPORT]?.jsonPrimitive?.long }.getOrNull()
            val atActor = runCatching { existing[ACTOR]?.jsonPrimitive?.content }.getOrNull().orEmpty()
            if (!wins(op.lamport, op.actorId, atLamport, atActor)) return sceneJson
            elements.removeAt(existingIndex)
        }
        // Recorded even when the element was not here: the peer that has not seen the add yet must
        // still refuse it when it arrives, or the two peers disagree about whether it exists.
        return writeScene(parsed, elements, tombstones + (op.elementId to Grave(op.lamport, op.actorId)))
    }

    private data class Grave(val lamport: Long, val actorId: String)

    private fun tombstonesOf(scene: JsonObject): Map<String, Grave> {
        val raw = runCatching { scene[TOMBSTONES]?.jsonObject }.getOrNull() ?: return emptyMap()
        return raw.mapNotNull { (id, value) ->
            val obj = runCatching { value.jsonObject }.getOrNull() ?: return@mapNotNull null
            val lamport = runCatching { obj[LAMPORT]?.jsonPrimitive?.long }.getOrNull() ?: return@mapNotNull null
            val actor = runCatching { obj[ACTOR]?.jsonPrimitive?.content }.getOrNull().orEmpty()
            id to Grave(lamport, actor)
        }.toMap()
    }

    private fun writeScene(
        parsed: JsonObject,
        elements: List<kotlinx.serialization.json.JsonElement>,
        tombstones: Map<String, Grave>,
    ): String {
        // Newest kept; see MAX_TOMBSTONES. Ordered by actor as well so every peer evicts the same
        // ones and the scenes stay byte-identical.
        val kept = tombstones.entries
            .sortedWith(compareByDescending<Map.Entry<String, Grave>> { it.value.lamport }.thenBy { it.key })
            .take(MAX_TOMBSTONES)
            .sortedBy { it.key }
        return canonicalScene(
            buildMap {
                parsed.forEach { (key, value) -> if (key != "elements" && key != TOMBSTONES) put(key, value) }
                put("elements", JsonArray(elements))
                if (kept.isNotEmpty()) {
                    put(
                        TOMBSTONES,
                        buildJsonObject {
                            kept.forEach { (id, grave) ->
                                put(
                                    id,
                                    buildJsonObject {
                                        put(LAMPORT, JsonPrimitive(grave.lamport))
                                        put(ACTOR, JsonPrimitive(grave.actorId))
                                    },
                                )
                            }
                        },
                    )
                }
            },
        )
    }

    private fun idOf(element: kotlinx.serialization.json.JsonElement): String? =
        runCatching { element.jsonObject["id"]?.jsonPrimitive?.content }.getOrNull()

    private fun parseScene(sceneJson: String): JsonObject {
        if (sceneJson.isBlank()) return parseEmptyScene()
        return try {
            json.parseToJsonElement(sceneJson).jsonObject
        } catch (_: Exception) {
            parseEmptyScene()
        }
    }

    private fun parseEmptyScene(): JsonObject = buildJsonObject {
        put("bgColor", JsonPrimitive(DEFAULT_BG_COLOR))
        put("elements", JsonArray(emptyList()))
    }

    fun emptySceneJson(): String = json.encodeToString(JsonObject.serializer(), parseEmptyScene())

    private fun parseElementWithMetadata(
        elementId: String,
        elementJson: String,
        lamport: Long,
        actorId: String,
    ): JsonObject {
        val parsed = try {
            json.parseToJsonElement(elementJson).jsonObject
        } catch (_: Exception) {
            JsonObject(emptyMap())
        }
        return buildJsonObject {
            put("id", JsonPrimitive(elementId))
            parsed.forEach { (key, value) ->
                if (key != "id" && key != LAMPORT && key != ACTOR) put(key, value)
            }
            put(LAMPORT, JsonPrimitive(lamport))
            put(ACTOR, JsonPrimitive(actorId))
        }
    }

    /**
     * Strips the synchronization bookkeeping, producing a scene DrawBox's strict deserializer
     * accepts. Everything this projector adds is `_`-prefixed for exactly this reason.
     */
    fun stripMetadataForDrawBox(sceneJson: String): String {
        if (sceneJson.isBlank()) return emptySceneJson()
        return try {
            val parsed = json.parseToJsonElement(sceneJson).jsonObject
            val elements = parsed["elements"]?.jsonArray?.map { elem ->
                val elemObj = elem.jsonObject
                buildJsonObject {
                    elemObj.forEach { (k, v) -> if (!k.startsWith("_")) put(k, v) }
                }
            } ?: emptyList()
            val cleaned = buildJsonObject {
                parsed.forEach { (k, v) -> if (k != "elements" && !k.startsWith("_")) put(k, v) }
                put("elements", JsonArray(elements))
            }
            json.encodeToString(JsonObject.serializer(), cleaned)
        } catch (_: Exception) {
            sceneJson
        }
    }
}
