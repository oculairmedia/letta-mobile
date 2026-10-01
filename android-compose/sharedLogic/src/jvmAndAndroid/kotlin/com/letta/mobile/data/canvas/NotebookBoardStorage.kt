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
        val elements = (read.get(ObjectId.ROOT, ELEMENTS).orElse(null) as? AmValue.Map)
        val fields = (read.get(ObjectId.ROOT, FIELDS).orElse(null) as? AmValue.Map)
        val tombstones = (read.get(ObjectId.ROOT, TOMBSTONES).orElse(null) as? AmValue.Map)
        val keys = elements?.let { read.keys(it.id).orElseThrow().toSet() }.orEmpty()
        val deleted = tombstones?.let { read.keys(it.id).orElseThrow().toSet() }.orEmpty()
        val fieldKeys = fields?.let { read.keys(it.id).orElseThrow().toList() }.orEmpty()
        if (keys.isEmpty() && deleted.isEmpty() && fieldKeys.isEmpty()) return raw
        val root = Json.parseToJsonElement(raw).jsonObject
        val rawElements = (root["elements"] as? JsonArray).orEmpty()
        val legacy = rawElements.filter { idOf(it) == null }
        val rawIds = rawElements.mapNotNull(::idOf)
        val orderedKeys = rawIds.filter { it in keys && it !in deleted }.distinct() +
            (keys - deleted - rawIds.toSet()).sorted()
        val values = if (elements == null) emptyList() else orderedKeys.mapNotNull { key ->
            val map = (read.get(elements.id, key).orElse(null) as? AmValue.Map)?.id ?: return@mapNotNull null
            readStringMap(read, map)
        }
        val objectFields = if (fields == null) emptyMap() else fieldKeys.mapNotNull { key ->
            (read.get(fields.id, key).orElse(null) as? AmValue.Map)?.let { key to readStringMap(read, it.id) }
        }.toMap()
        val merged = LinkedHashMap<String, JsonElement>()
        root.forEach { (key, value) -> merged[key] = objectFields[key] ?: value }
        objectFields.forEach { (key, value) -> if (key !in merged) merged[key] = value }
        merged["elements"] = JsonArray(legacy + values)
        return JsonObject(merged).toString()
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
        val live = Json.parseToJsonElement(boardJson(read)).jsonObject["elements"] as? JsonArray
        val liveIds = live.orEmpty().mapNotNull(::idOf).toSet()
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
        val map = (tx.get(elements, key).orElse(null) as? AmValue.Map)?.id ?: tx.set(elements, key, ObjectType.MAP)
        tx.keys(map).orElseThrow().filter { it !in value }.forEach { tx.delete(map, it) }
        value.forEach { (field, fieldValue) -> setStringIfChanged(tx, map, field, fieldValue.toString()) }
        val tombstones = (tx.get(ObjectId.ROOT, TOMBSTONES).orElse(null) as? AmValue.Map)
        if (tombstones != null && tx.get(tombstones.id, key).isPresent) tx.delete(tombstones.id, key)
        val deleted = (tx.get(ObjectId.ROOT, DELETED).orElse(null) as? AmValue.Map)
        if (deleted != null && tx.get(deleted.id, key).isPresent) tx.delete(deleted.id, key)
    }

    /** Remove an element, keeping its last body for restore and a tombstone against stale writers. */
    fun removeElement(tx: Transaction, key: String) {
        val elements = elementsMap(tx)
        val previous = (tx.get(elements, key).orElse(null) as? AmValue.Map)?.id
        if (previous != null) {
            tx.set(deletedMap(tx), key, readStringMap(tx, previous).toString())
            tx.delete(elements, key)
        }
        val tombstones = (tx.get(ObjectId.ROOT, TOMBSTONES).orElse(null) as? AmValue.Map)?.id
            ?: tx.set(ObjectId.ROOT, TOMBSTONES, ObjectType.MAP)
        if ((tx.get(tombstones, key).orElse(null) as? AmValue.Bool)?.value != true) tx.set(tombstones, key, true)
    }

    /** Drop an element without a restore snapshot (a notebook-side delete). */
    fun deleteElement(tx: Transaction, key: String) {
        val elements = elementsMap(tx)
        if (tx.get(elements, key).isPresent) tx.delete(elements, key)
        val tombstones = (tx.get(ObjectId.ROOT, TOMBSTONES).orElse(null) as? AmValue.Map)?.id
            ?: tx.set(ObjectId.ROOT, TOMBSTONES, ObjectType.MAP)
        tx.set(tombstones, key, true)
    }

    /**
     * Apply one canvas write: only elements and scene fields that differ between the canvas's
     * previous write ([base]) and this one are written, so notebook-side edits survive and
     * unchanged content records nothing.
     */
    fun mergeCanvasBoard(tx: Transaction, base: SceneBase, incoming: JsonObject) {
        val current = Json.parseToJsonElement(boardJson(tx)).jsonObject
        val liveIds = (current["elements"] as? JsonArray).orEmpty().mapNotNull(::idOf).toSet()
        val next = addressableElements(incoming)
        for (key in base.elements.keys + next.keys) {
            val nextValue = next[key]
            val previousDigest = base.elements[key]
            if (previousDigest == nextValue?.let(::digest)) continue
            if (nextValue == null) {
                removeElement(tx, key)
            } else if (previousDigest == null || key in liveIds) {
                putElement(tx, key, nextValue)
            }
        }
        val envelope = LinkedHashMap<String, JsonElement>(current)
        for (key in (base.fields.keys + incoming.keys).filter { it != "elements" && it != "schema" }) {
            val value = incoming[key]
            if (base.fields[key] == value?.let(::digest)) continue
            if (value != null) envelope[key] = value else envelope.remove(key)
        }
        writeEnvelope(tx, JsonObject(envelope))
    }

    /** The board string holds ordering stubs and scalar fields; object fields go entry by entry. */
    private fun writeEnvelope(tx: Transaction, board: JsonObject) {
        val objectFields = board.filter { (key, value) -> key != "elements" && key != "schema" && value is JsonObject }
            .mapValues { it.value as JsonObject }
        val raw = JsonObject(board.mapValues { (key, value) ->
            when {
                key == "elements" -> JsonArray((value as? JsonArray).orEmpty().map(::stub))
                key in objectFields -> JsonObject(emptyMap())
                else -> value
            }
        }).toString()
        setStringIfChanged(tx, ObjectId.ROOT, BOARD, raw)
        val existing = (tx.get(ObjectId.ROOT, FIELDS).orElse(null) as? AmValue.Map)?.id
        if (existing == null && objectFields.isEmpty()) return
        // A reader of layout 1 would see the `{}` placeholders as empty fields and write them back.
        if (layoutVersion(tx) < LAYOUT_VERSION) tx.set(ObjectId.ROOT, LAYOUT, LAYOUT_VERSION)
        val fields = existing ?: tx.set(ObjectId.ROOT, FIELDS, ObjectType.MAP)
        tx.keys(fields).orElseThrow().filter { it !in objectFields }.forEach { tx.delete(fields, it) }
        objectFields.forEach { (key, value) ->
            val map = (tx.get(fields, key).orElse(null) as? AmValue.Map)?.id ?: tx.set(fields, key, ObjectType.MAP)
            tx.keys(map).orElseThrow().filter { it !in value }.forEach { tx.delete(map, it) }
            value.forEach { (entry, entryValue) -> setStringIfChanged(tx, map, entry, entryValue.toString()) }
        }
    }

    private fun stub(element: JsonElement): JsonElement =
        idOf(element)?.let { JsonObject(mapOf("id" to JsonPrimitive(it))) } ?: element

    // ---- canvas base ------------------------------------------------------------------------

    fun sceneBaseOf(sceneJson: String): SceneBase {
        val board = sceneBoard(sceneJson)
        return SceneBase(
            blank = sceneJson.isEmpty(),
            elements = addressableElements(board).mapValues { digest(it.value) },
            fields = board.filterKeys { it != "elements" && it != "schema" }.mapValues { digest(it.value) },
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
        val map = (read.get(ObjectId.ROOT, key).orElse(null) as? AmValue.Map)?.id ?: return emptyMap()
        return read.keys(map).orElseThrow().associateWith { (read.get(map, it).orElseThrow() as AmValue.Str).value }
    }

    fun writeSceneBase(tx: Transaction, base: SceneBase) {
        if ((tx.get(ObjectId.ROOT, BASE_BLANK).orElse(null) as? AmValue.Bool)?.value != base.blank) {
            tx.set(ObjectId.ROOT, BASE_BLANK, base.blank)
        }
        writeDigests(tx, BASE_ELEMENTS, base.elements)
        writeDigests(tx, BASE_FIELDS, base.fields)
        // The old form stored the whole scene again on every save; it is not written any more.
        if (tx.get(ObjectId.ROOT, LEGACY_BASE).isPresent) tx.delete(ObjectId.ROOT, LEGACY_BASE)
    }

    private fun writeDigests(tx: Transaction, key: String, digests: Map<String, String>) {
        val map = (tx.get(ObjectId.ROOT, key).orElse(null) as? AmValue.Map)?.id ?: tx.set(ObjectId.ROOT, key, ObjectType.MAP)
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
        val handled = setOf(ELEMENTS, TOMBSTONES, DELETED, LEGACY_BASE, BASE_BLANK, BASE_ELEMENTS, BASE_FIELDS)
        for (key in source.keys(ObjectId.ROOT).orElseThrow()) {
            if (key in skip || key in handled) continue
            NotebookHistoryArchive.copyValue(source, source.get(ObjectId.ROOT, key).orElseThrow(), tx, ObjectId.ROOT, key)
        }
        val hidden = (source.get(ObjectId.ROOT, TOMBSTONES).orElse(null) as? AmValue.Map)
            ?.let { source.keys(it.id).orElseThrow().toSet() }.orEmpty()
        // Created even when empty, as a new notebook has them: two peers creating one concurrently
        // would each make their own map, and one map's entries would be lost.
        val elements = tx.set(ObjectId.ROOT, ELEMENTS, ObjectType.MAP)
        tx.set(ObjectId.ROOT, TOMBSTONES, ObjectType.MAP)
        val restore = tx.set(ObjectId.ROOT, DELETED, ObjectType.MAP)
        (source.get(ObjectId.ROOT, ELEMENTS).orElse(null) as? AmValue.Map)?.let { from ->
            for (key in source.keys(from.id).orElseThrow()) {
                if (key !in hidden) NotebookHistoryArchive.copyValue(source, source.get(from.id, key).orElseThrow(), tx, elements, key)
            }
        }
        val snapshots = (source.get(ObjectId.ROOT, DELETED).orElse(null) as? AmValue.Map)?.let { map ->
            source.keys(map.id).orElseThrow().mapNotNull { key ->
                (source.get(map.id, key).orElse(null) as? AmValue.Str)?.value?.let { key to it }
            }
        }.orEmpty()
        val live = (Json.parseToJsonElement(boardJson(source)).jsonObject["elements"] as? JsonArray).orEmpty().mapNotNull(::idOf).toSet()
        val restorable = snapshots.filter { (key, _) -> key !in live }.sortedWith(compareBy({ it.second.length }, { it.first }))
        var bytes = 0L
        val kept = restorable.takeWhile { (key, value) ->
            bytes += key.length + value.length
            bytes <= restoreListBytes
        }
        kept.forEach { (key, value) -> tx.set(restore, key, value) }
        readSceneBase(source)?.let { writeSceneBase(tx, it) }
        return restorable.size - kept.size
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

    private fun elementsMap(tx: Transaction): ObjectId =
        (tx.get(ObjectId.ROOT, ELEMENTS).orElse(null) as? AmValue.Map)?.id
            ?: tx.set(ObjectId.ROOT, ELEMENTS, ObjectType.MAP)

    private fun deletedMap(tx: Transaction): ObjectId =
        (tx.get(ObjectId.ROOT, DELETED).orElse(null) as? AmValue.Map)?.id
            ?: tx.set(ObjectId.ROOT, DELETED, ObjectType.MAP)

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
