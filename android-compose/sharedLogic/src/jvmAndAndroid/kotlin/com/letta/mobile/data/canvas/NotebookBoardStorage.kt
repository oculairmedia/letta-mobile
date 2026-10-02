package com.letta.mobile.data.canvas

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.automerge.AmValue
import org.automerge.ObjectId
import org.automerge.ObjectType
import org.automerge.Read
import org.automerge.Transaction

/**
 * How a notebook board sits in its Automerge document. Automerge keeps every value ever written,
 * so a save must write only what changed: history then grows with the edits, not with the size of
 * the board times the number of saves. (Writing the whole scene on every save is how one phone's
 * 112 KB board became a 62 MB snapshot.)
 *
 * - `ROOT.board`: a JSON string with the scene's scalar and array fields, `elements` as ordering
 *   stubs (`{"id": …}`) plus id-less legacy elements, and `{}` placeholders for object fields.
 * - `ROOT.boardFields`: map of object-valued scene field (`_documents`, `_arrowBindings`, …) to a
 *   map of entry to JSON string, so editing one note rewrites that note, not every note.
 * - `ROOT.boardElements`: map of element id to a map of field to JSON string. A field is written
 *   only when its value changed, so moving an element rewrites its geometry, not its style.
 *
 * Older documents keep full element bodies and object fields inline in `ROOT.board`; readers
 * accept both and the next write moves them out.
 *
 * The canvas layer's last-written scene ("base", for its three-way merge) is kept as digests
 * (`canvasBaseElements`, `canvasBaseFields`), not as a second full copy of the scene.
 */
internal object NotebookBoardStorage {
    const val SCHEMA = "notebook-board/1"
    const val EMPTY_BOARD = "{\"schema\":\"notebook-board/1\",\"elements\":[]}"

    /**
     * The board layout this build reads and writes, in `ROOT.boardVersion`. 1: everything inline
     * in `ROOT.board` plus `ROOT.boardElements`. 2: object fields moved to `ROOT.boardFields`,
     * leaving `{}` placeholders in `ROOT.board`; written the first time a document gets
     * `boardFields`. A document with a higher version is read but never written (see
     * [NotebookLocalStore]), so this build cannot clobber fields it does not know about. Builds
     * from before the marker do not read it; that residual risk is in the PR that added it.
     */
    const val LAYOUT_VERSION = 2
    private const val LAYOUT = "boardVersion"

    fun layoutVersion(read: Read): Long = when (val value = read.get(ObjectId.ROOT, LAYOUT).orElse(null)) {
        is AmValue.Int -> value.value
        is AmValue.UInt -> value.value
        else -> 1L
    }

    private const val BOARD = "board"
    private const val FIELDS = "boardFields"
    private const val ELEMENTS = "boardElements"
    private const val TOMBSTONES = "boardTombstones"
    private const val DELETED = "boardDeletedElements"
    private const val LEGACY_BASE = "canvasSceneBase"
    private const val BASE_BLANK = "canvasBaseBlank"
    private const val BASE_ELEMENTS = "canvasBaseElements"
    private const val BASE_FIELDS = "canvasBaseFields"

    /** The canvas layer's last-written scene, reduced to what its merge compares. */
    data class SceneBase(
        val blank: Boolean,
        val elements: Map<String, String>,
        val fields: Map<String, String>,
    )

    // ---- reading ------------------------------------------------------------------------

    fun boardJson(read: Read): String {
        val raw = (read.get(ObjectId.ROOT, BOARD).orElseThrow() as AmValue.Str).value
        val stored = StoredBoard(read)
        if (stored.isInline) return raw
        val root = Json.parseToJsonElement(raw).jsonObject
        val objectFields = stored.objectFields()
        val merged = LinkedHashMap<String, JsonElement>()
        root.forEach { (key, value) -> merged[key] = objectFields[key] ?: value }
        objectFields.forEach { (key, value) -> if (key !in merged) merged[key] = value }
        merged["elements"] = JsonArray(stored.elements(root))
        return JsonObject(merged).toString()
    }

    /** The parts of a board kept outside `ROOT.board`: element bodies, tombstones and object fields. */
    private class StoredBoard(private val read: Read) {
        private val elementMap = mapAt(read, ObjectId.ROOT, ELEMENTS)
        private val fieldMap = mapAt(read, ObjectId.ROOT, FIELDS)
        private val keys = keysOf(read, elementMap).toSet()
        private val deleted = keysOf(read, mapAt(read, ObjectId.ROOT, TOMBSTONES)).toSet()
        private val fieldKeys = keysOf(read, fieldMap)

        /** Nothing is kept outside the board string: it is the whole board. */
        val isInline: Boolean get() = listOf(keys, deleted, fieldKeys).all { it.isEmpty() }

        /** Id-less legacy elements, then live elements in the board string's order, then the rest by id. */
        fun elements(root: JsonObject): List<JsonElement> {
            val rawElements = (root["elements"] as? JsonArray).orEmpty()
            val rawIds = rawElements.mapNotNull(::idOf)
            val orderedKeys = rawIds.filter { it in keys && it !in deleted }.distinct() +
                (keys - deleted - rawIds.toSet()).sorted()
            return rawElements.filter { idOf(it) == null } + readMaps(elementMap, orderedKeys).values
        }

        fun objectFields(): Map<String, JsonObject> = readMaps(fieldMap, fieldKeys)

        /** The maps of JSON strings under [parent] at [entries], in that order; missing ones skipped. */
        private fun readMaps(parent: ObjectId?, entries: List<String>): Map<String, JsonObject> {
            if (parent == null) return emptyMap()
            return entries.mapNotNull { key -> mapAt(read, parent, key)?.let { key to readStringMap(read, it) } }.toMap()
        }
    }

    /** A map of JSON strings as a JSON object. */
    private fun readStringMap(read: Read, map: ObjectId): JsonObject =
        JsonObject(read.keys(map).orElseThrow().associateWith { field ->
            Json.parseToJsonElement((read.get(map, field).orElseThrow() as AmValue.Str).value)
        })

    fun isPristine(read: Read): Boolean {
        val raw = (read.get(ObjectId.ROOT, BOARD).orElseThrow() as AmValue.Str).value
        return raw == EMPTY_BOARD
    }

    fun deletedElements(read: Read): List<CanvasDeletedElement> {
        val snapshots = (read.get(ObjectId.ROOT, DELETED).orElse(null) as? AmValue.Map) ?: return emptyList()
        val liveIds = liveIdsOf(read)
        return read.keys(snapshots.id).orElseThrow().mapNotNull { key ->
            if (key in liveIds) null else (read.get(snapshots.id, key).orElse(null) as? AmValue.Str)?.value?.let {
                CanvasDeletedElement(key, it)
            }
        }
    }

    // ---- writing ------------------------------------------------------------------------

    /** Replace the whole board: elements not in [board] are dropped. */
    fun writeBoard(tx: Transaction, board: JsonObject) {
        val elements = elementsMap(tx)
        val incoming = addressableElements(board)
        for (key in tx.keys(elements).orElseThrow()) {
            if (key !in incoming) tx.delete(elements, key)
        }
        incoming.forEach { (key, value) -> putElement(tx, key, value) }
        writeEnvelope(tx, board)
    }

    /** Write one element, touching only the fields whose value changed. */
    fun putElement(tx: Transaction, key: String, value: JsonObject) {
        val elements = elementsMap(tx)
        putStrings(tx, mapAt(tx, elements, key) ?: tx.set(elements, key, ObjectType.MAP), value)
        mapAt(tx, ObjectId.ROOT, TOMBSTONES)?.let { deleteIfPresent(tx, it, key) }
        mapAt(tx, ObjectId.ROOT, DELETED)?.let { deleteIfPresent(tx, it, key) }
    }

    /** Remove an element, keeping its last body for restore and a tombstone against stale writers. */
    fun removeElement(tx: Transaction, key: String) {
        val elements = elementsMap(tx)
        val previous = mapAt(tx, elements, key)
        if (previous != null) {
            tx.set(deletedMap(tx), key, readStringMap(tx, previous).toString())
            tx.delete(elements, key)
        }
        val tombstones = mapAt(tx, ObjectId.ROOT, TOMBSTONES) ?: tx.set(ObjectId.ROOT, TOMBSTONES, ObjectType.MAP)
        if ((tx.get(tombstones, key).orElse(null) as? AmValue.Bool)?.value != true) tx.set(tombstones, key, true)
    }

    /** Drop an element without a restore snapshot (a notebook-side delete). */
    fun deleteElement(tx: Transaction, key: String) {
        deleteIfPresent(tx, elementsMap(tx), key)
        val tombstones = mapAt(tx, ObjectId.ROOT, TOMBSTONES) ?: tx.set(ObjectId.ROOT, TOMBSTONES, ObjectType.MAP)
        tx.set(tombstones, key, true)
    }

    /**
     * Apply one canvas write: only elements and scene fields that differ between the canvas's
     * previous write ([base]) and this one are written, so notebook-side edits survive and
     * unchanged content records nothing.
     */
    fun mergeCanvasBoard(tx: Transaction, base: SceneBase, incoming: JsonObject) {
        val current = Json.parseToJsonElement(boardJson(tx)).jsonObject
        mergeElements(tx, base, incoming, liveIdsOf(current))
        writeEnvelope(tx, mergedEnvelope(current, base, incoming))
    }

    /**
     * Elements the canvas changed since [base]: removed ones are removed, and changed ones are
     * written unless the notebook side deleted them meanwhile (not in [liveIds]).
     */
    private fun mergeElements(tx: Transaction, base: SceneBase, incoming: JsonObject, liveIds: Set<String>) {
        val next = addressableElements(incoming)
        for (key in base.elements.keys + next.keys) {
            val nextValue = next[key]
            val previousDigest = base.elements[key]
            when {
                previousDigest == nextValue?.let(::digest) -> Unit
                nextValue == null -> removeElement(tx, key)
                previousDigest == null || key in liveIds -> putElement(tx, key, nextValue)
            }
        }
    }

    /** [current]'s scene fields with those the canvas changed since [base] taken from [incoming]. */
    private fun mergedEnvelope(current: JsonObject, base: SceneBase, incoming: JsonObject): JsonObject {
        val envelope = LinkedHashMap<String, JsonElement>(current)
        for (key in (base.fields.keys + incoming.keys).filter(::isSceneField)) {
            val value = incoming[key]
            when {
                base.fields[key] == value?.let(::digest) -> Unit
                value != null -> envelope[key] = value
                else -> envelope.remove(key)
            }
        }
        return JsonObject(envelope)
    }

    /** The board string holds ordering stubs and scalar fields; object fields go entry by entry. */
    private fun writeEnvelope(tx: Transaction, board: JsonObject) {
        val objectFields = board.filter { (key, value) -> isSceneField(key) && value is JsonObject }
            .mapValues { it.value as JsonObject }
        setStringIfChanged(tx, ObjectId.ROOT, BOARD, stubbedBoard(board, objectFields.keys))
        val existing = mapAt(tx, ObjectId.ROOT, FIELDS)
        if (existing == null && objectFields.isEmpty()) return
        // A reader of layout 1 would see the `{}` placeholders as empty fields and write them back.
        if (layoutVersion(tx) < LAYOUT_VERSION) tx.set(ObjectId.ROOT, LAYOUT, LAYOUT_VERSION)
        writeStringMaps(tx, existing ?: tx.set(ObjectId.ROOT, FIELDS, ObjectType.MAP), objectFields)
    }

    /** [board] with elements as ordering stubs and each of [objectFields] as a `{}` placeholder. */
    private fun stubbedBoard(board: JsonObject, objectFields: Set<String>): String =
        JsonObject(board.mapValues { (key, value) ->
            when {
                key == "elements" -> JsonArray((value as? JsonArray).orEmpty().map(::stub))
                key in objectFields -> JsonObject(emptyMap())
                else -> value
            }
        }).toString()

    /** [parent] holds one map of JSON strings per key of [values]; other keys are dropped. */
    private fun writeStringMaps(tx: Transaction, parent: ObjectId, values: Map<String, JsonObject>) {
        tx.keys(parent).orElseThrow().filter { it !in values }.forEach { tx.delete(parent, it) }
        values.forEach { (key, value) -> putStrings(tx, mapAt(tx, parent, key) ?: tx.set(parent, key, ObjectType.MAP), value) }
    }

    /** [map] holds [value]'s fields as JSON strings, writing only those that changed. */
    private fun putStrings(tx: Transaction, map: ObjectId, value: JsonObject) {
        tx.keys(map).orElseThrow().filter { it !in value }.forEach { tx.delete(map, it) }
        value.forEach { (field, fieldValue) -> setStringIfChanged(tx, map, field, fieldValue.toString()) }
    }

    private fun stub(element: JsonElement): JsonElement =
        idOf(element)?.let { JsonObject(mapOf("id" to JsonPrimitive(it))) } ?: element

    // ---- canvas base ------------------------------------------------------------------------

    fun sceneBaseOf(sceneJson: String): SceneBase {
        val board = sceneBoard(sceneJson)
        return SceneBase(
            blank = sceneJson.isEmpty(),
            elements = addressableElements(board).mapValues { digest(it.value) },
            fields = board.filterKeys(::isSceneField).mapValues { digest(it.value) },
        )
    }

    /** Whether the canvas's last write was a blank scene; null if the canvas never wrote. */
    fun baseIsBlank(read: Read): Boolean? {
        (read.get(ObjectId.ROOT, BASE_BLANK).orElse(null) as? AmValue.Bool)?.let { return it.value }
        return (read.get(ObjectId.ROOT, LEGACY_BASE).orElse(null) as? AmValue.Str)?.value?.isEmpty()
    }

    fun readSceneBase(read: Read): SceneBase? {
        val blank = (read.get(ObjectId.ROOT, BASE_BLANK).orElse(null) as? AmValue.Bool)?.value
        if (blank == null) {
            return (read.get(ObjectId.ROOT, LEGACY_BASE).orElse(null) as? AmValue.Str)?.value?.let(::sceneBaseOf)
        }
        return SceneBase(blank, readDigests(read, BASE_ELEMENTS), readDigests(read, BASE_FIELDS))
    }

    private fun readDigests(read: Read, key: String): Map<String, String> {
        val map = mapAt(read, ObjectId.ROOT, key) ?: return emptyMap()
        return read.keys(map).orElseThrow().associateWith { (read.get(map, it).orElseThrow() as AmValue.Str).value }
    }

    fun writeSceneBase(tx: Transaction, base: SceneBase) {
        if ((tx.get(ObjectId.ROOT, BASE_BLANK).orElse(null) as? AmValue.Bool)?.value != base.blank) {
            tx.set(ObjectId.ROOT, BASE_BLANK, base.blank)
        }
        writeDigests(tx, BASE_ELEMENTS, base.elements)
        writeDigests(tx, BASE_FIELDS, base.fields)
        // The old form stored the whole scene again on every save; it is not written any more.
        deleteIfPresent(tx, ObjectId.ROOT, LEGACY_BASE)
    }

    private fun writeDigests(tx: Transaction, key: String, digests: Map<String, String>) {
        val map = mapAt(tx, ObjectId.ROOT, key) ?: tx.set(ObjectId.ROOT, key, ObjectType.MAP)
        tx.keys(map).orElseThrow().filter { it !in digests }.forEach { tx.delete(map, it) }
        digests.forEach { (entry, value) -> setStringIfChanged(tx, map, entry, value) }
    }

    // ---- moving to a new document ---------------------------------------------------------

    /**
     * Copy [source]'s current state into a new document's first transaction, keeping only what the
     * board needs (see [NotebookHistoryArchive.compactToNewDocument]); root keys in [skip] are left
     * to the caller. Left behind, and kept only in the archive, whose final state has all of it:
     * - Tombstones, and the element bodies they hide. They only mask writes made concurrently with
     *   a delete in the old history, which never merges into the new document; the board reads the
     *   same without them ([sameContent] checks).
     * - Deleted-element snapshots (the history view's restore list) past [restoreListBytes]. Kept
     *   ones are chosen smallest first, which keeps the most elements restorable; the store records
     *   no deletion times to keep the most recent instead.
     * - The legacy full-scene copy of the canvas base; the base is written as digests.
     * Returns how many restore-list entries were left in the archive.
     */
    fun copyCurrentState(source: Read, tx: Transaction, skip: Set<String>, restoreListBytes: Long): Int {
        val copy = AutomergeTreeCopy(source, tx)
        val handled = setOf(ELEMENTS, TOMBSTONES, DELETED, LEGACY_BASE, BASE_BLANK, BASE_ELEMENTS, BASE_FIELDS)
        source.keys(ObjectId.ROOT).orElseThrow().filter { it !in skip && it !in handled }
            .forEach { copy.copyEntry(ObjectId.ROOT, ObjectId.ROOT, it) }
        val hidden = keysOf(source, mapAt(source, ObjectId.ROOT, TOMBSTONES)).toSet()
        // Created even when empty, as a new notebook has them: two peers creating one concurrently
        // would each make their own map, and one map's entries would be lost.
        val elements = tx.set(ObjectId.ROOT, ELEMENTS, ObjectType.MAP)
        tx.set(ObjectId.ROOT, TOMBSTONES, ObjectType.MAP)
        val restore = tx.set(ObjectId.ROOT, DELETED, ObjectType.MAP)
        mapAt(source, ObjectId.ROOT, ELEMENTS)?.let { from ->
            source.keys(from).orElseThrow().filter { it !in hidden }.forEach { copy.copyEntry(from, elements, it) }
        }
        val restorable = restorableSnapshots(source)
        val kept = keptWithin(restorable, restoreListBytes)
        kept.forEach { (key, value) -> tx.set(restore, key, value) }
        readSceneBase(source)?.let { writeSceneBase(tx, it) }
        return restorable.size - kept.size
    }

    /** Deleted-element snapshots of elements not on the board, smallest first. */
    private fun restorableSnapshots(source: Read): List<Pair<String, String>> {
        val map = mapAt(source, ObjectId.ROOT, DELETED) ?: return emptyList()
        val live = liveIdsOf(source)
        return source.keys(map).orElseThrow()
            .filter { it !in live }
            .mapNotNull { key -> (source.get(map, key).orElse(null) as? AmValue.Str)?.value?.let { key to it } }
            .sortedWith(compareBy({ it.second.length }, { it.first }))
    }

    /** The leading [snapshots] whose keys and bodies fit in [limitBytes]. */
    private fun keptWithin(snapshots: List<Pair<String, String>>, limitBytes: Long): List<Pair<String, String>> {
        var bytes = 0L
        return snapshots.takeWhile { (key, value) ->
            bytes += key.length + value.length
            bytes <= limitBytes
        }
    }

    /** Whether [a] and [b] read as the same notebook: board, canvas base, title, text, items and metadata. */
    fun sameContent(a: Read, b: Read): Boolean {
        fun string(read: Read, key: String) = (read.get(ObjectId.ROOT, key).orElse(null) as? AmValue.Str)?.value
        fun text(read: Read) = (read.get(ObjectId.ROOT, "markdown").orElse(null) as? AmValue.Text)?.let { read.text(it.id).orElse(null) }
        fun items(read: Read) = (read.get(ObjectId.ROOT, "items").orElse(null) as? AmValue.Map)?.let { map ->
            read.keys(map.id).orElseThrow().associateWith { (read.get(map.id, it).orElse(null) as? AmValue.Str)?.value }
        }
        return Json.parseToJsonElement(boardJson(a)) == Json.parseToJsonElement(boardJson(b)) &&
            readSceneBase(a) == readSceneBase(b) &&
            baseIsBlank(a) == baseIsBlank(b) &&
            layoutVersion(a) == layoutVersion(b) &&
            listOf("title", "canvasMetadata", "schema").all { string(a, it) == string(b, it) } &&
            text(a) == text(b) &&
            items(a) == items(b)
    }

    // ---- helpers ------------------------------------------------------------------------

    fun sceneBoard(scene: String): JsonObject {
        val root = if (scene.isBlank()) JsonObject(mapOf("elements" to JsonArray(emptyList())))
            else Json.parseToJsonElement(scene).jsonObject
        require(root["elements"] is JsonArray) { "Canvas scene needs elements" }
        return JsonObject(root + ("schema" to JsonPrimitive(SCHEMA)))
    }

    fun addressableElements(root: JsonObject): Map<String, JsonObject> =
        (root["elements"] as? JsonArray).orEmpty().mapNotNull { value ->
            (value as? JsonObject)?.let { obj -> idOf(obj)?.let { it to obj } }
        }.toMap()

    private fun idOf(value: JsonElement): String? =
        ((value as? JsonObject)?.get("id") as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    /** A scene field other than the element list and the schema marker. */
    private fun isSceneField(key: String): Boolean = key != "elements" && key != "schema"

    /** The ids of the elements [read]'s board shows. */
    private fun liveIdsOf(read: Read): Set<String> = liveIdsOf(Json.parseToJsonElement(boardJson(read)).jsonObject)

    private fun liveIdsOf(board: JsonObject): Set<String> = (board["elements"] as? JsonArray).orEmpty().mapNotNull(::idOf).toSet()

    /** The map at [key] of [parent]; null if there is none or it is not a map. */
    private fun mapAt(read: Read, parent: ObjectId, key: String): ObjectId? =
        (read.get(parent, key).orElse(null) as? AmValue.Map)?.id

    private fun keysOf(read: Read, map: ObjectId?): List<String> = map?.let { read.keys(it).orElseThrow().toList() }.orEmpty()

    private fun deleteIfPresent(tx: Transaction, map: ObjectId, key: String) {
        if (tx.get(map, key).isPresent) tx.delete(map, key)
    }

    private fun elementsMap(tx: Transaction): ObjectId =
        mapAt(tx, ObjectId.ROOT, ELEMENTS) ?: tx.set(ObjectId.ROOT, ELEMENTS, ObjectType.MAP)

    private fun deletedMap(tx: Transaction): ObjectId =
        mapAt(tx, ObjectId.ROOT, DELETED) ?: tx.set(ObjectId.ROOT, DELETED, ObjectType.MAP)

    /** Automerge records a put even when the value is the same; skip those. */
    fun setStringIfChanged(tx: Transaction, map: ObjectId, key: String, value: String) {
        if ((tx.get(map, key).orElse(null) as? AmValue.Str)?.value != value) tx.set(map, key, value)
    }

    /** Equal JSON (object key order aside) has equal digests. */
    fun digest(value: JsonElement): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(canonical(value).toByteArray(Charsets.UTF_8))
        return bytes.take(DIGEST_BYTES).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private fun canonical(value: JsonElement): String = when (value) {
        is JsonObject -> value.entries.sortedBy { it.key }
            .joinToString(",", "{", "}") { (key, item) -> JsonPrimitive(key).toString() + ":" + canonical(item) }
        is JsonArray -> value.joinToString(",", "[", "]") { canonical(it) }
        else -> value.toString()
    }

    private const val DIGEST_BYTES = 16
}
