package com.letta.mobile.data.canvas

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
    /** Every provenance key ends this way: `_lamport`, `_bgLamport`, `_bgPatternLamport`. */
    private const val LAMPORT_SUFFIX = "lamport"
    private const val ACTOR = "_actorId"
    /** The final tie-break: every device edits as the same user actor with its own Lamport clock. */
    private const val OP_ID = "_opId"
    private const val BG_OP_ID = "_bgOpId"
    private const val BG_PATTERN_OP_ID = "_bgPatternOpId"
    private const val TOMBSTONES = "_removed"
    private const val BG_LAMPORT = "_bgLamport"
    private const val BG_ACTOR = "_bgActorId"
    private const val ARROW_BINDINGS = "_arrowBindings"
    private const val LABEL_OWNERS = "_labelOwners"
    private const val BINDING_VALUE = "binding"
    private const val BG_PATTERN = "_bgPattern"
    private const val BG_PATTERN_LAMPORT = "_bgPatternLamport"
    private const val BG_PATTERN_ACTOR = "_bgPatternActorId"
    private const val DOCUMENTS = "_documents"
    private const val DOC_JSON = "json"
    private const val DOC_REMOVED = "_removed"
    private const val DOC_FRAME = "frame"
    private const val DOC_COLOR = "color"
    private const val DOC_STYLE = "style"
    private const val DOC_TITLE = "title"

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

    private data class WriterProvenance(val lamport: Long, val actorId: String, val opId: String = "")

    private val bgPropertyKeys = setOf("bgColor", BG_LAMPORT, BG_ACTOR, BG_OP_ID)
    private val bgPatternKeys = setOf(BG_PATTERN, BG_PATTERN_LAMPORT, BG_PATTERN_ACTOR, BG_PATTERN_OP_ID)
    private val metadataPropertyKeys = setOf("id", LAMPORT, ACTOR, OP_ID)

    /**
     * A write with [write] beats one already recorded at [against], by the total order
     * (lamport, actorId, opId) - [CanvasOpOrder]. The op id matters: every device edits as the same
     * user actor with its own Lamport clock, so two devices can write one element at an equal
     * (lamport, actorId), and without it the later arrival won - a different winner per device.
     */
    private fun wins(write: WriterProvenance, against: WriterProvenance?): Boolean =
        against == null || CanvasOpOrder.compare(
            write.lamport, write.actorId, write.opId,
            against.lamport, against.actorId, against.opId,
        ) >= 0

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
        is CanvasOp.ReplaceSceneOp -> replaceScene(sceneJson, op)
        is CanvasOp.SetDocumentOp -> upsertDocumentWithLww(sceneJson, op)
        is CanvasOp.RemoveDocumentOp -> removeDocumentWithLww(sceneJson, op)
        is CanvasOp.SetBackgroundOp -> setBackground(sceneJson, op)
        is CanvasOp.SetBackgroundPatternOp -> setBackgroundPattern(sceneJson, op)
        is CanvasOp.SetArrowBindingOp -> setArrowBinding(sceneJson, op)
        is CanvasOp.SetLabelOwnerOp -> setLabelOwner(sceneJson, op)
        is CanvasOp.AddElementOp -> upsertElementWithLww(sceneJson, op)
        is CanvasOp.UpdateElementOp -> upsertElementWithLww(sceneJson, op)
        is CanvasOp.RemoveElementOp -> removeElementWithLww(sceneJson, op)
        is CanvasOp.BatchOp -> project(sceneJson, op.ops)
    }

    /**
     * A replace is authoritative over everything before it, so its elements are stamped with its
     * own lamport and actor. Otherwise they would arrive with no provenance and the next
     * out-of-order element op — however old — would win against them.
     */
    private fun stampElement(element: JsonElement, provenance: WriterProvenance): JsonElement {
        val obj = runCatching { element.jsonObject }.getOrNull() ?: return element
        val id = runCatching { obj["id"]?.jsonPrimitive?.content }.getOrNull().orEmpty()
        val payload = json.encodeToString(JsonObject.serializer(), obj)
        return parseElementWithMetadata(ElementMetadataInput(id, payload, provenance))
    }

    private fun stampElements(elements: JsonArray?, provenance: WriterProvenance): List<JsonElement> =
        elements?.map { stampElement(it, provenance) } ?: emptyList()

    private fun replaceScene(sceneJson: String, op: CanvasOp.ReplaceSceneOp): String {
        val incoming = if (op.sceneJson.isNotBlank()) parseScene(op.sceneJson) else parseEmptyScene()
        // A replace is a drawing, not a notebook: it carries the block documents of the scene it
        // replaces unless it brings its own, so an agent redraw never erases the notes.
        //
        // Ownership is carried with them. A label is only a label because the scene records which
        // shape owns it, and that record is what lets the reconciler move it, re-frame it and
        // take it away with its shape. Dropped by a replace, every label survived as an ordinary
        // note that belonged to nothing: it stayed where the old shape was, and nothing would
        // ever clean it up.
        val current = parseScene(sceneJson)
        val carriedDocuments = if (incoming.containsKey(DOCUMENTS)) null else current[DOCUMENTS]
        val carriedLabelOwners = if (carriedDocuments != null && !incoming.containsKey(LABEL_OWNERS)) current[LABEL_OWNERS] else null
        val provenance = WriterProvenance(op.lamport, op.actorId, op.opId)
        val stamped = stampElements(incoming["elements"]?.jsonArray, provenance)
        return canonicalScene(
            buildMap {
                incoming.forEach { (key, value) -> if (key != "elements") put(key, value) }
                carriedDocuments?.let { put(DOCUMENTS, it) }
                carriedLabelOwners?.let { put(LABEL_OWNERS, it) }
                put("elements", JsonArray(stamped))
                put(BG_LAMPORT, JsonPrimitive(op.lamport))
                put(BG_ACTOR, JsonPrimitive(op.actorId))
                put(BG_OP_ID, JsonPrimitive(op.opId))
            },
        )
    }

    private fun setBackground(sceneJson: String, op: CanvasOp.SetBackgroundOp): String {
        val parsed = parseScene(sceneJson)
        val atLamport = runCatching { parsed[BG_LAMPORT]?.jsonPrimitive?.long }.getOrNull()
        val atActor = runCatching { parsed[BG_ACTOR]?.jsonPrimitive?.content }.getOrNull().orEmpty()
        val atOpId = runCatching { parsed[BG_OP_ID]?.jsonPrimitive?.content }.getOrNull().orEmpty()
        val currentProvenance = atLamport?.let { WriterProvenance(it, atActor, atOpId) }
        val opProvenance = WriterProvenance(op.lamport, op.actorId, op.opId)
        // The background is scene-level state with its own history; comparing it against arrival
        // order alone let two peers settle on different colours (review B2).
        if (!wins(opProvenance, currentProvenance)) return sceneJson
        return canonicalScene(
            buildMap {
                parsed.forEach { (key, value) ->
                    if (key !in bgPropertyKeys) put(key, value)
                }
                put("bgColor", JsonPrimitive(op.colorHex))
                put(BG_LAMPORT, JsonPrimitive(op.lamport))
                put(BG_ACTOR, JsonPrimitive(op.actorId))
                put(BG_OP_ID, JsonPrimitive(op.opId))
            },
        )
    }

    /** Every connector's document bindings on the scene root, by element id. */
    fun arrowBindingsOf(sceneJson: String): Map<String, CanvasArrowBinding> {
        val parsed = parseScene(sceneJson)
        val table = runCatching { parsed[ARROW_BINDINGS]?.jsonObject }.getOrNull() ?: return emptyMap()
        return table.mapNotNull { (id, entry) ->
            val value = runCatching { entry.jsonObject[BINDING_VALUE] }.getOrNull() ?: return@mapNotNull null
            runCatching { json.decodeFromJsonElement(CanvasArrowBinding.serializer(), value) }.getOrNull()?.let { id to it }
        }.toMap()
    }

    /** LWW per connector, keyed by element id; an entry with both ends null is kept as the unbinding. */
    private fun setArrowBinding(sceneJson: String, op: CanvasOp.SetArrowBindingOp): String {
        val parsed = parseScene(sceneJson)
        val table = runCatching { parsed[ARROW_BINDINGS]?.jsonObject }.getOrNull().orEmpty()
        val existing = table[op.elementId]?.let { runCatching { it.jsonObject }.getOrNull() }
        val atLamport = runCatching { existing?.get(LAMPORT)?.jsonPrimitive?.long }.getOrNull()
        val atActor = runCatching { existing?.get(ACTOR)?.jsonPrimitive?.content }.getOrNull().orEmpty()
        val atOpId = runCatching { existing?.get(OP_ID)?.jsonPrimitive?.content }.getOrNull().orEmpty()
        if (!wins(WriterProvenance(op.lamport, op.actorId, op.opId), atLamport?.let { WriterProvenance(it, atActor, atOpId) })) return sceneJson
        val entry = buildJsonObject {
            put(BINDING_VALUE, json.encodeToJsonElement(CanvasArrowBinding.serializer(), op.binding))
            put(LAMPORT, JsonPrimitive(op.lamport))
            put(ACTOR, JsonPrimitive(op.actorId))
            put(OP_ID, JsonPrimitive(op.opId))
        }
        val updated = buildJsonObject {
            table.forEach { (id, value) -> if (id != op.elementId) put(id, value) }
            put(op.elementId, entry)
        }
        return canonicalScene(
            buildMap {
                parsed.forEach { (key, value) -> if (key != ARROW_BINDINGS) put(key, value) }
                put(ARROW_BINDINGS, updated)
            },
        )
    }

    /**
     * Which shape owns which label document, by document id.
     *
     * A document is a shape's label because this says so, not because of what it is called.
     */
    fun labelOwnersOf(sceneJson: String): Map<String, String> {
        val parsed = parseScene(sceneJson)
        val table = runCatching { parsed[LABEL_OWNERS]?.jsonObject }.getOrNull() ?: return emptyMap()
        return table.mapNotNull { (documentId, entry) ->
            val shapeId = runCatching { entry.jsonObject[BINDING_VALUE]?.jsonPrimitive?.content }.getOrNull()
            shapeId?.takeIf { it.isNotBlank() }?.let { documentId to it }
        }.toMap()
    }

    /** LWW per document id; a null shape is kept as the release, so a peer cannot re-claim it. */
    private fun setLabelOwner(sceneJson: String, op: CanvasOp.SetLabelOwnerOp): String {
        val parsed = parseScene(sceneJson)
        val table = runCatching { parsed[LABEL_OWNERS]?.jsonObject }.getOrNull().orEmpty()
        val existing = table[op.documentId]?.let { runCatching { it.jsonObject }.getOrNull() }
        val atLamport = runCatching { existing?.get(LAMPORT)?.jsonPrimitive?.long }.getOrNull()
        val atActor = runCatching { existing?.get(ACTOR)?.jsonPrimitive?.content }.getOrNull().orEmpty()
        val atOpId = runCatching { existing?.get(OP_ID)?.jsonPrimitive?.content }.getOrNull().orEmpty()
        if (!wins(WriterProvenance(op.lamport, op.actorId, op.opId), atLamport?.let { WriterProvenance(it, atActor, atOpId) })) return sceneJson
        val entry = buildJsonObject {
            put(BINDING_VALUE, JsonPrimitive(op.shapeId.orEmpty()))
            put(LAMPORT, JsonPrimitive(op.lamport))
            put(ACTOR, JsonPrimitive(op.actorId))
            put(OP_ID, JsonPrimitive(op.opId))
        }
        val updated = buildJsonObject {
            table.forEach { (id, value) -> if (id != op.documentId) put(id, value) }
            put(op.documentId, entry)
        }
        return canonicalScene(
            buildMap {
                parsed.forEach { (key, value) -> if (key != LABEL_OWNERS) put(key, value) }
                put(LABEL_OWNERS, updated)
            },
        )
    }

    /** The pattern on the scene root, or null when none was ever set (the board draws none). */
    fun backgroundPatternOf(sceneJson: String): CanvasBackgroundPattern? {
        val parsed = parseScene(sceneJson)
        val element = parsed[BG_PATTERN] ?: return null
        return runCatching { json.decodeFromJsonElement(CanvasBackgroundPattern.serializer(), element) }.getOrNull()
    }

    /** Same LWW as [setBackground], on the pattern's own provenance keys. */
    private fun setBackgroundPattern(sceneJson: String, op: CanvasOp.SetBackgroundPatternOp): String {
        val parsed = parseScene(sceneJson)
        val atLamport = runCatching { parsed[BG_PATTERN_LAMPORT]?.jsonPrimitive?.long }.getOrNull()
        val atActor = runCatching { parsed[BG_PATTERN_ACTOR]?.jsonPrimitive?.content }.getOrNull().orEmpty()
        val atOpId = runCatching { parsed[BG_PATTERN_OP_ID]?.jsonPrimitive?.content }.getOrNull().orEmpty()
        val currentProvenance = atLamport?.let { WriterProvenance(it, atActor, atOpId) }
        if (!wins(WriterProvenance(op.lamport, op.actorId, op.opId), currentProvenance)) return sceneJson
        return canonicalScene(
            buildMap {
                parsed.forEach { (key, value) ->
                    if (key !in bgPatternKeys) put(key, value)
                }
                put(BG_PATTERN, json.encodeToJsonElement(CanvasBackgroundPattern.serializer(), op.pattern))
                put(BG_PATTERN_LAMPORT, JsonPrimitive(op.lamport))
                put(BG_PATTERN_ACTOR, JsonPrimitive(op.actorId))
                put(BG_PATTERN_OP_ID, JsonPrimitive(op.opId))
            },
        )
    }

    private fun upsertElementWithLww(sceneJson: String, op: CanvasOp): String {
        val (elementId, elementJson) = when (op) {
            is CanvasOp.AddElementOp -> op.elementId to op.elementJson
            is CanvasOp.UpdateElementOp -> op.elementId to op.elementJson
            else -> return sceneJson
        }
        val opProvenance = WriterProvenance(op.lamport, op.actorId, op.opId)
        val parsed = parseScene(sceneJson)
        val tombstones = tombstonesOf(parsed)
        // A removal the element never came back from still counts, even though the element is gone.
        tombstones[elementId]?.let { grave ->
            if (!wins(opProvenance, grave)) return sceneJson
        }

        val elements = parsed["elements"]?.jsonArray?.toMutableList() ?: mutableListOf()
        val existingIndex = elements.indexOfFirst { idOf(it) == elementId }
        if (existingIndex >= 0) {
            val existing = elements[existingIndex].jsonObject
            val atLamport = runCatching { existing[LAMPORT]?.jsonPrimitive?.long }.getOrNull()
            val atActor = runCatching { existing[ACTOR]?.jsonPrimitive?.content }.getOrNull().orEmpty()
            val atOpId = runCatching { existing[OP_ID]?.jsonPrimitive?.content }.getOrNull().orEmpty()
            val existingProvenance = atLamport?.let { WriterProvenance(it, atActor, atOpId) }
            if (!wins(opProvenance, existingProvenance)) return sceneJson
        }

        val newElement = parseElementWithMetadata(ElementMetadataInput(elementId, elementJson, opProvenance))
        if (existingIndex >= 0) elements[existingIndex] = newElement else elements.add(newElement)
        // The write won, so any tombstone for this id is now history.
        return writeScene(parsed, elements, tombstones - elementId)
    }

    private fun removeElementWithLww(sceneJson: String, op: CanvasOp.RemoveElementOp): String {
        val parsed = parseScene(sceneJson)
        val tombstones = tombstonesOf(parsed)
        val opProvenance = WriterProvenance(op.lamport, op.actorId, op.opId)
        tombstones[op.elementId]?.let { grave ->
            if (!wins(opProvenance, grave)) return sceneJson
        }

        val elements = parsed["elements"]?.jsonArray?.toMutableList() ?: mutableListOf()
        val existingIndex = elements.indexOfFirst { idOf(it) == op.elementId }
        if (existingIndex >= 0) {
            val existing = elements[existingIndex].jsonObject
            val atLamport = runCatching { existing[LAMPORT]?.jsonPrimitive?.long }.getOrNull()
            val atActor = runCatching { existing[ACTOR]?.jsonPrimitive?.content }.getOrNull().orEmpty()
            val atOpId = runCatching { existing[OP_ID]?.jsonPrimitive?.content }.getOrNull().orEmpty()
            val existingProvenance = atLamport?.let { WriterProvenance(it, atActor, atOpId) }
            if (!wins(opProvenance, existingProvenance)) return sceneJson
            elements.removeAt(existingIndex)
        }
        // Recorded even when the element was not here: the peer that has not seen the add yet must
        // still refuse it when it arrives, or the two peers disagree about whether it exists.
        return writeScene(parsed, elements, tombstones + (op.elementId to opProvenance))
    }

    /** The live block documents of [sceneJson], removed ones excluded, ordered by id. */
    fun documentsOf(sceneJson: String): List<CanvasSceneDocument> {
        if (sceneJson.isBlank()) return emptyList()
        val parsed = runCatching { parseScene(sceneJson) }.getOrNull() ?: return emptyList()
        return documentEntries(parsed)
            .filter { !isRemovedDocument(it) }
            .mapNotNull { entry ->
                val id = runCatching { entry["id"]?.jsonPrimitive?.content }.getOrNull() ?: return@mapNotNull null
                val json = runCatching { entry[DOC_JSON]?.jsonPrimitive?.content }.getOrNull() ?: return@mapNotNull null
                CanvasSceneDocument(
                    id = id,
                    json = json,
                    frame = documentFrame(entry),
                    color = documentColor(entry),
                    style = documentStyle(entry),
                    title = documentTitle(entry),
                )
            }
            .sortedBy { it.id }
    }

    private fun documentEntries(scene: JsonObject): List<JsonObject> =
        runCatching { scene[DOCUMENTS]?.jsonArray }.getOrNull().orEmpty().mapNotNull { runCatching { it.jsonObject }.getOrNull() }

    private fun isRemovedDocument(entry: JsonObject): Boolean =
        runCatching { entry[DOC_REMOVED]?.jsonPrimitive?.content == "true" }.getOrNull() == true

    private fun documentFrame(entry: JsonObject): CanvasDocumentFrame? {
        val frame = runCatching { entry[DOC_FRAME]?.jsonObject }.getOrNull() ?: return null
        fun number(key: String): Float? = runCatching { frame[key]?.jsonPrimitive?.content?.toFloat() }.getOrNull()
        return CanvasDocumentFrame(
            x = number("x") ?: return null,
            y = number("y") ?: return null,
            width = number("width") ?: return null,
            height = number("height") ?: return null,
        )
    }

    private fun documentColor(entry: JsonObject): String? =
        runCatching { entry[DOC_COLOR]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun documentTitle(entry: JsonObject): String? =
        runCatching { entry[DOC_TITLE]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun documentStyle(entry: JsonObject): CanvasTextStyle? {
        val raw = runCatching { entry[DOC_STYLE]?.jsonObject }.getOrNull() ?: return null
        return runCatching { json.decodeFromJsonElement(CanvasTextStyle.serializer(), raw) }.getOrNull()
    }

    private fun styleJson(style: CanvasTextStyle): JsonObject =
        json.encodeToJsonElement(CanvasTextStyle.serializer(), style).jsonObject

    private fun frameJson(frame: CanvasDocumentFrame): JsonObject = buildJsonObject {
        put("x", JsonPrimitive(frame.x))
        put("y", JsonPrimitive(frame.y))
        put("width", JsonPrimitive(frame.width))
        put("height", JsonPrimitive(frame.height))
    }

    private fun documentProvenance(entry: JsonObject): WriterProvenance? {
        val lamport = runCatching { entry[LAMPORT]?.jsonPrimitive?.long }.getOrNull() ?: return null
        val actor = runCatching { entry[ACTOR]?.jsonPrimitive?.content }.getOrNull().orEmpty()
        val opId = runCatching { entry[OP_ID]?.jsonPrimitive?.content }.getOrNull().orEmpty()
        return WriterProvenance(lamport, actor, opId)
    }
    private data class DocumentWriteInput(
        val documentId: String,
        val provenance: WriterProvenance,
        val json: String?,
        val frame: CanvasDocumentFrame? = null,
        val color: String? = null,
        val style: CanvasTextStyle? = null,
        val title: String? = null,
    )

    /**
     * Last writer wins per document id, like elements. A removal stays in the array as a removed
     * entry carrying its provenance, so an older write that arrives late still loses to it.
     * A write without a [frame] or [color] keeps what the document already had; a removal drops both.
     */
    private fun writeDocument(
        sceneJson: String,
        input: DocumentWriteInput,
    ): String {
        val parsed = parseScene(sceneJson)
        val entries = documentEntries(parsed).toMutableList()
        val index = entries.indexOfFirst { documentIdOf(it) == input.documentId }
        if (index >= 0 && !wins(input.provenance, documentProvenance(entries[index]))) return sceneJson
        val existing = entries.getOrNull(index)?.takeIf { !isRemovedDocument(it) }
        val entry = documentEntry(input, existing)
        if (index >= 0) entries[index] = entry else entries.add(entry)
        entries.sortBy { documentIdOf(it).orEmpty() }
        return canonicalScene(
            buildMap {
                parsed.forEach { (key, value) -> if (key != DOCUMENTS) put(key, value) }
                put(DOCUMENTS, JsonArray(entries))
            },
        )
    }

    private fun documentIdOf(entry: JsonObject): String? = runCatching { entry["id"]?.jsonPrimitive?.content }.getOrNull()

    /**
     * The entry [input] leaves for its document: its text (or a removal), what it sets or [existing]
     * already had, and its provenance.
     */
    private fun documentEntry(input: DocumentWriteInput, existing: JsonObject?): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(input.documentId))
        if (input.json == null) {
            put(DOC_REMOVED, JsonPrimitive(true))
        } else {
            put(DOC_JSON, JsonPrimitive(input.json))
            keptFields(input, existing).forEach { (key, value) -> put(key, value) }
        }
        put(LAMPORT, JsonPrimitive(input.provenance.lamport))
        put(ACTOR, JsonPrimitive(input.provenance.actorId))
        put(OP_ID, JsonPrimitive(input.provenance.opId))
    }

    /** A write without a frame, colour, style or title keeps [existing]'s; an empty title clears it. */
    private fun keptFields(input: DocumentWriteInput, existing: JsonObject?): Map<String, JsonElement> = buildMap {
        (input.frame ?: existing?.let(::documentFrame))?.let { put(DOC_FRAME, frameJson(it)) }
        (input.color ?: existing?.let(::documentColor))?.let { put(DOC_COLOR, JsonPrimitive(it)) }
        (input.style ?: existing?.let(::documentStyle))?.let { put(DOC_STYLE, styleJson(it)) }
        (input.title ?: existing?.let(::documentTitle))?.takeIf { it.isNotBlank() }?.let { put(DOC_TITLE, JsonPrimitive(it)) }
    }

    private fun upsertDocumentWithLww(sceneJson: String, op: CanvasOp.SetDocumentOp): String =
        writeDocument(
            sceneJson,
            DocumentWriteInput(
                documentId = op.documentId,
                provenance = WriterProvenance(op.lamport, op.actorId, op.opId),
                json = op.documentJson,
                frame = op.frame,
                color = op.color,
                style = op.style,
                title = op.title,
            ),
        )

    private fun removeDocumentWithLww(sceneJson: String, op: CanvasOp.RemoveDocumentOp): String =
        writeDocument(
            sceneJson,
            DocumentWriteInput(
                documentId = op.documentId,
                provenance = WriterProvenance(op.lamport, op.actorId, op.opId),
                json = null,
            ),
        )

    private fun tombstonesOf(scene: JsonObject): Map<String, WriterProvenance> {
        val raw = runCatching { scene[TOMBSTONES]?.jsonObject }.getOrNull() ?: return emptyMap()
        return raw.mapNotNull { (id, value) ->
            val obj = runCatching { value.jsonObject }.getOrNull() ?: return@mapNotNull null
            val lamport = runCatching { obj[LAMPORT]?.jsonPrimitive?.long }.getOrNull() ?: return@mapNotNull null
            val actor = runCatching { obj[ACTOR]?.jsonPrimitive?.content }.getOrNull().orEmpty()
            val opId = runCatching { obj[OP_ID]?.jsonPrimitive?.content }.getOrNull().orEmpty()
            id to WriterProvenance(lamport, actor, opId)
        }.toMap()
    }

    private fun writeScene(
        parsed: JsonObject,
        elements: List<kotlinx.serialization.json.JsonElement>,
        tombstones: Map<String, WriterProvenance>,
    ): String {
        // Newest kept; see MAX_TOMBSTONES. Ordered by actor as well so every peer evicts the same
        // ones and the scenes stay byte-identical.
        val kept = tombstones.entries
            .sortedWith(compareByDescending<Map.Entry<String, WriterProvenance>> { it.value.lamport }.thenBy { it.key })
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
                                        put(OP_ID, JsonPrimitive(grave.opId))
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

    private data class ElementMetadataInput(
        val elementId: String,
        val elementJson: String,
        val provenance: WriterProvenance,
    )

    private fun parseElementWithMetadata(input: ElementMetadataInput): JsonObject {
        val parsed = try {
            json.parseToJsonElement(input.elementJson).jsonObject
        } catch (_: Exception) {
            JsonObject(emptyMap())
        }
        return buildJsonObject {
            put("id", JsonPrimitive(input.elementId))
            parsed.forEach { (key, value) ->
                if (key !in metadataPropertyKeys) put(key, value)
            }
            put(LAMPORT, JsonPrimitive(input.provenance.lamport))
            put(ACTOR, JsonPrimitive(input.provenance.actorId))
            put(OP_ID, JsonPrimitive(input.provenance.opId))
        }
    }

    private fun cleanElementsArray(elements: JsonArray): List<JsonObject> = elements.mapNotNull { elem ->
        val elemObj = runCatching { elem.jsonObject }.getOrNull() ?: return@mapNotNull null
        buildJsonObject {
            elemObj.forEach { (k, v) -> if (!k.startsWith("_")) put(k, v) }
        }
    }

    private fun cleanSceneRoot(parsed: JsonObject, cleanedElements: List<JsonObject>): JsonObject = buildJsonObject {
        parsed.forEach { (k, v) -> if (k != "elements" && !k.startsWith("_")) put(k, v) }
        put("elements", JsonArray(cleanedElements))
    }

    /**
     * Strips the synchronization bookkeeping, producing a scene DrawBox's strict deserializer
     * accepts. Everything this projector adds is `_`-prefixed for exactly this reason.
     */
    /**
     * Whether two DrawBox scenes draw the same thing: the same elements (in any order, as sets)
     * and the same root properties. Key order and formatting differ between what DrawBox exports
     * and what the projector stores, so a string compare would call every autosave a change.
     */
    fun drawingsEqual(a: String?, b: String?): Boolean {
        if (a == b) return true
        if (a == null || b == null) return false
        val pa = runCatching { json.parseToJsonElement(a).jsonObject }.getOrNull() ?: return false
        val pb = runCatching { json.parseToJsonElement(b).jsonObject }.getOrNull() ?: return false
        val ea = runCatching { pa["elements"]?.jsonArray?.toSet() }.getOrNull().orEmpty()
        val eb = runCatching { pb["elements"]?.jsonArray?.toSet() }.getOrNull().orEmpty()
        if (ea != eb) return false
        return pa.filterKeys { it != "elements" } == pb.filterKeys { it != "elements" }
    }

    /**
     * The highest lamport any writer left anywhere in [sceneJson] - elements, their tombstones,
     * documents, the background, the bindings.
     *
     * A session that adopts an existing scene has to start its clock above this. Its writes are
     * settled last-writer-wins against exactly these numbers, so a clock starting from zero makes
     * every edit older than what it is editing and the projector keeps the existing value: the
     * change is dropped, silently, with no error anywhere to say so.
     *
     * Read by walking the whole tree for any `*lamport` key rather than by visiting the keys
     * this file happens to name today, so a provenance added later cannot quietly fall outside it.
     */
    fun maxLamport(sceneJson: String): Long {
        if (sceneJson.isBlank()) return 0L
        val root = runCatching { json.parseToJsonElement(sceneJson) }.getOrNull() ?: return 0L
        return maxLamportOf(root)
    }

    private fun maxLamportOf(element: kotlinx.serialization.json.JsonElement): Long = when (element) {
        is JsonObject -> element.entries.maxOfOrNull { (key, value) ->
            val own = if (key.endsWith(LAMPORT_SUFFIX, ignoreCase = true)) {
                runCatching { value.jsonPrimitive.long }.getOrNull() ?: 0L
            } else {
                0L
            }
            maxOf(own, maxLamportOf(value))
        } ?: 0L
        is JsonArray -> element.maxOfOrNull { maxLamportOf(it) } ?: 0L
        else -> 0L
    }

    fun stripMetadataForDrawBox(sceneJson: String): String {
        if (sceneJson.isBlank()) return emptySceneJson()
        return try {
            val parsed = json.parseToJsonElement(sceneJson).jsonObject
            val elements = parsed["elements"]?.jsonArray
            val cleanedElements = if (elements != null) cleanElementsArray(elements) else emptyList()
            val cleaned = cleanSceneRoot(parsed, cleanedElements)
            json.encodeToString(JsonObject.serializer(), cleaned)
        } catch (_: Exception) {
            sceneJson
        }
    }
}
