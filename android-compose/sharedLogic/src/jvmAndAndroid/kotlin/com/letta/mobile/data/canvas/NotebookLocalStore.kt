package com.letta.mobile.data.canvas

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.automerge.AmValue
import org.automerge.ObjectId
import org.automerge.ObjectType
import org.automerge.Read
import org.automerge.Transaction
import org.automerge.repo.DocHandle
import org.automerge.repo.DocumentId
import org.automerge.repo.PeerId
import org.automerge.repo.Repo
import org.automerge.repo.RepoConfig
import org.automerge.repo.storage.FileSystemStorage

/** One peer's durable notebook documents, independent of App Server conversations. */
class NotebookLocalStore(directory: Path, peerId: String) : AutoCloseable {
    @Serializable
    private data class DocumentIndex(val ids: List<String>)

    private val canvasLockFile = directory.resolve("notebook-canvas.lock")
    private val projection = NotebookFilesystemProjection(directory.resolve("projection"))

    /** Serialize canvas claims and CAS across instances and processes using this repository. */
    internal fun <T> withCanvasLock(action: () -> T): T {
        require(!Files.isSymbolicLink(canvasLockFile)) { "Canvas lock must not be a symlink" }
        FileChannel.open(canvasLockFile, CREATE, WRITE).use { channel ->
            while (true) {
                try {
                    channel.lock().use { return action() }
                } catch (_: OverlappingFileLockException) {
                    Thread.sleep(10)
                }
            }
        }
    }
    private val indexFile = directory.resolve("notebook-documents.json")
    private val poller = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "notebook-projection-poller").apply { isDaemon = true }
    }
    private var polling: ScheduledFuture<*>? = null
    private var closed = false

    /** Indexed notebooks include local creations and explicitly registered remote documents. */
    @Synchronized
    fun listDocuments(): List<DocumentId> = readIndex().map { key ->
        DocumentId.fromBytes(key.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
    }

    /** Reconcile previously projected documents; new documents are exported by startPolling. */
    @Synchronized
    fun pollProjections(): Map<DocumentId, NotebookProjectionResult> = listDocuments()
        .associateWith { id -> projectionResult { if (projection.hasBaseline(id)) reconcile(id) else null } }
        .filterValues { it != null }
        .mapValues { it.value!! }

    /** Opt-in lifecycle polling; report conflicts to the caller rather than overwriting either side. */
    @Synchronized
    fun startPolling(intervalMillis: Long, onResult: (DocumentId, NotebookProjectionResult) -> Unit = { _, _ -> }) {
        require(intervalMillis > 0)
        check(!closed) { "Notebook store is closed" }
        check(polling == null) { "Projection polling already started" }
        polling = poller.scheduleWithFixedDelay({
            try {
                listDocuments().forEach { id ->
                    val result = projectionResult { if (projection.hasBaseline(id)) reconcile(id) else project(id) }
                    try { onResult(id, result!!) } catch (_: Exception) { /* A callback must not stop future polls. */ }
                }
            } catch (_: Exception) { /* Index errors are retried on the next poll. */ }
        }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    fun stopPolling() {
        polling?.cancel(false)
        polling = null
    }

    private fun readIndex(): List<String> {
        if (!Files.exists(indexFile, NOFOLLOW_LINKS)) return emptyList()
        require(Files.isRegularFile(indexFile, NOFOLLOW_LINKS)) { "Not a regular notebook index: $indexFile" }
        val ids = Json.decodeFromString<DocumentIndex>(Files.readString(indexFile, UTF_8)).ids
        require(ids.all { it.length == 32 && it.all { char -> char in '0'..'9' || char in 'a'..'f' } } && ids.distinct().size == ids.size) {
            "Invalid notebook document index"
        }
        return ids
    }

    /** Register a known remote document after it is available in this repository. */
    @Synchronized
    fun registerDocument(id: DocumentId) {
        check(!closed) { "Notebook store is closed" }
        requireNotNull(open(id)) { "Unknown notebook document: $id" }
        index(id)
    }

    private fun index(id: DocumentId) {
        val key = id.stableKey()
        val previous = readIndex()
        if (key in previous) return
        val ids = previous + key
        val temp = Files.createTempFile(indexFile.parent, ".notebook-index-", ".tmp")
        try {
            Files.writeString(temp, Json.encodeToString(DocumentIndex.serializer(), DocumentIndex(ids)), UTF_8)
            Files.move(temp, indexFile, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    /** Poll for external edits; returns a conflict without changing either copy if both changed. */
    @Synchronized
    fun reconcile(id: DocumentId): NotebookProjectionResult = projectionResult { projection.reconcile(this, id) }!!

    /** Write current notebook state to its filesystem representation. */
    @Synchronized
    fun project(id: DocumentId): NotebookProjectionResult = projectionResult { projection.project(this, id) }!!

    data class NotebookContent(
        val title: String,
        val markdown: String,
        val board: String,
    )

    private fun projectionResult(block: () -> NotebookProjectionResult?): NotebookProjectionResult? =
        try { block() } catch (_: Exception) { NotebookProjectionResult.ERROR }

    internal data class CanvasImportData(
        val sourceId: String,
        val title: String,
        val board: String,
    )

    internal fun replaceProjection(id: DocumentId, expected: NotebookDocument, content: NotebookContent): Boolean {
        val handle = requireNotNull(open(id)) { "Unknown notebook document: $id" }
        return handle.withDocument { document ->
            document.startTransaction().use { tx ->
                val textId = (tx.get(ObjectId.ROOT, "markdown").orElseThrow() as AmValue.Text).id
                val previous = tx.text(textId).orElseThrow()
                val currentTitle = (tx.get(ObjectId.ROOT, "title").orElseThrow() as AmValue.Str).value
                val currentBoard = boardFrom(tx)
                if (previous != expected.markdown) return@withDocument false
                if (currentTitle != expected.title) return@withDocument false
                if (currentBoard != expected.sceneJson) return@withDocument false
                tx.spliceText(textId, 0, previous.length.toLong(), content.markdown)
                tx.set(ObjectId.ROOT, "title", content.title)
                writeBoard(tx, content.board)
                tx.commit()
                true
            }
        }.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    val repo: Repo = Repo.load(
        RepoConfig.builder()
            .storage(FileSystemStorage(directory))
            .peerId(PeerId.fromString(peerId))
            .build(),
    )

    @Synchronized
    fun create(title: String): DocumentId {
        val handle = repo.create().get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        handle.withDocument { document ->
            document.startTransaction().use { tx ->
                tx.set(ObjectId.ROOT, "schema", "notebook/1")
                tx.set(ObjectId.ROOT, "title", title)
                tx.set(ObjectId.ROOT, "initialTitle", title)
                tx.set(ObjectId.ROOT, "markdown", ObjectType.TEXT)
                tx.set(ObjectId.ROOT, "boardVersion", 1)
                tx.set(ObjectId.ROOT, "board", "{\"schema\":\"notebook-board/1\",\"elements\":[]}")
                tx.set(ObjectId.ROOT, "boardElements", ObjectType.MAP)
                tx.set(ObjectId.ROOT, "boardTombstones", ObjectType.MAP)
                tx.set(ObjectId.ROOT, "items", ObjectType.MAP)
                tx.commit()
            }
        }.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        index(handle.documentId)
        return handle.documentId
    }

    fun open(id: DocumentId): DocHandle? =
        repo.find(id).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).orElse(null)

    private fun checkAlreadyImported(
        tx: Transaction,
        marker: AmValue?,
        importData: CanvasImportData,
    ): Boolean {
        if ((marker as? AmValue.Str)?.value != importData.sourceId) return false
        val importedBoard = (tx.get(ObjectId.ROOT, "importedBoard").orElse(null) as? AmValue.Str)?.value ?: return false
        if (importedBoard != importData.board) return false
        val currentBoard = boardFrom(tx)
        if (Json.parseToJsonElement(importedBoard) != Json.parseToJsonElement(currentBoard)) return false
        val currentTitle = (tx.get(ObjectId.ROOT, "title").orElseThrow() as AmValue.Str).value
        if (currentTitle != importData.title) return false
        val importedTitle = (tx.get(ObjectId.ROOT, "importedTitle").orElse(null) as? AmValue.Str)?.value
        if (currentTitle != importedTitle) return false
        val textId = (tx.get(ObjectId.ROOT, "markdown").orElseThrow() as AmValue.Text).id
        if (tx.text(textId).orElseThrow().isNotEmpty()) return false
        val itemsId = (tx.get(ObjectId.ROOT, "items").orElseThrow() as AmValue.Map).id
        return tx.keys(itemsId).orElseThrow().isEmpty()
    }

    private fun isPristineDocument(tx: Transaction): Boolean {
        val initialTitle = (tx.get(ObjectId.ROOT, "initialTitle").orElse(null) as? AmValue.Str)?.value ?: return false
        val currentTitle = (tx.get(ObjectId.ROOT, "title").orElseThrow() as AmValue.Str).value
        if (initialTitle != currentTitle) return false
        val textId = (tx.get(ObjectId.ROOT, "markdown").orElseThrow() as AmValue.Text).id
        if (tx.text(textId).orElseThrow().isNotEmpty()) return false
        val itemsId = (tx.get(ObjectId.ROOT, "items").orElseThrow() as AmValue.Map).id
        if (tx.keys(itemsId).orElseThrow().isNotEmpty()) return false
        val currentBoard = (tx.get(ObjectId.ROOT, "board").orElseThrow() as AmValue.Str).value
        return currentBoard == "{\"schema\":\"notebook-board/1\",\"elements\":[]}"
    }

    /** Claim a pristine, caller-selected notebook; never infer its ID from the canvas ID. */
    @Synchronized
    internal fun importCanvasInto(id: DocumentId, importData: CanvasImportData): NotebookCanvasImportResult {
        check(!closed) { "Notebook store is closed" }
        val handle = requireNotNull(open(id)) { "Unknown notebook document: $id" }
        return handle.withDocument { document ->
            document.startTransaction().use { tx ->
                val marker = tx.get(ObjectId.ROOT, "importedCanvasId").orElse(null)
                if (marker != null) {
                    return@withDocument if (checkAlreadyImported(tx, marker, importData)) {
                        NotebookCanvasImportResult.ALREADY_IMPORTED
                    } else {
                        NotebookCanvasImportResult.CONFLICT
                    }
                }
                if (!isPristineDocument(tx)) {
                    return@withDocument NotebookCanvasImportResult.CONFLICT
                }
                tx.set(ObjectId.ROOT, "title", importData.title)
                writeBoard(tx, importData.board)
                tx.set(ObjectId.ROOT, "importedCanvasId", importData.sourceId)
                tx.set(ObjectId.ROOT, "importedBoard", importData.board)
                tx.set(ObjectId.ROOT, "importedTitle", importData.title)
                tx.commit()
                NotebookCanvasImportResult.IMPORTED
            }
        }.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    /** Update one element without replacing other elements' Automerge registers. */
    fun putBoardElement(id: DocumentId, element: JsonObject) {
        val key = (element["id"] as? JsonPrimitive)?.content
        require(!key.isNullOrBlank()) { "Board element needs a stable id" }
        val handle = requireNotNull(open(id))
        handle.withDocument { document ->
            document.startTransaction().use { tx ->
                val elements = boardElements(tx)
                val existing = (tx.get(elements, key).orElse(null) as? AmValue.Map)?.id
                    ?: tx.set(elements, key, ObjectType.MAP)
                for (field in tx.keys(existing).orElseThrow()) {
                    if (field !in element) tx.delete(existing, field)
                }
                element.forEach { (field, value) -> tx.set(existing, field, value.toString()) }
                val tombstones = (tx.get(ObjectId.ROOT, "boardTombstones").orElse(null) as? AmValue.Map)
                if (tombstones != null && tx.get(tombstones.id, key).isPresent) tx.delete(tombstones.id, key)
                tx.commit()
            }
        }.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    fun removeBoardElement(id: DocumentId, elementId: String) {
        require(elementId.isNotBlank())
        requireNotNull(open(id)).withDocument { document ->
            document.startTransaction().use { tx ->
                val elements = boardElements(tx)
                if (tx.get(elements, elementId).isPresent) tx.delete(elements, elementId)
                val tombstones = (tx.get(ObjectId.ROOT, "boardTombstones").orElse(null) as? AmValue.Map)
                val tombstoneId = tombstones?.id ?: tx.set(ObjectId.ROOT, "boardTombstones", ObjectType.MAP)
                tx.set(tombstoneId, elementId, true)
                tx.commit()
            }
        }.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun boardElements(tx: Transaction): ObjectId =
        (tx.get(ObjectId.ROOT, "boardElements").orElse(null) as? AmValue.Map)?.id
            ?: tx.set(ObjectId.ROOT, "boardElements", ObjectType.MAP)

    private fun parseIncomingElements(root: JsonObject): Map<String, JsonObject> =
        (root["elements"] as? JsonArray).orEmpty().mapNotNull { value ->
            (value as? JsonObject)?.let { obj ->
                (obj["id"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let { it to obj }
            }
        }.toMap()

    private fun deleteMissingElements(tx: Transaction, elements: ObjectId, incomingKeys: Set<String>) {
        for (key in tx.keys(elements).orElseThrow()) {
            if (key !in incomingKeys) tx.delete(elements, key)
        }
    }

    private fun writeBoard(tx: Transaction, boardJson: String) {
        val root = Json.parseToJsonElement(boardJson).jsonObject
        val elements = boardElements(tx)
        val incoming = parseIncomingElements(root)
        deleteMissingElements(tx, elements, incoming.keys)
        incoming.forEach { (key, value) -> putElement(tx, key, value) }
        // The envelope retains unknown metadata and non-addressable legacy entries.
        tx.set(ObjectId.ROOT, "board", boardJson)
    }

    private fun boardFrom(read: Read): String {
        val raw = (read.get(ObjectId.ROOT, "board").orElseThrow() as AmValue.Str).value
        val elements = (read.get(ObjectId.ROOT, "boardElements").orElse(null) as? AmValue.Map) ?: return raw
        val tombstones = (read.get(ObjectId.ROOT, "boardTombstones").orElse(null) as? AmValue.Map)
        val keys = read.keys(elements.id).orElseThrow().toSet()
        val deleted = tombstones?.let { read.keys(it.id).orElseThrow().toSet() }.orEmpty()
        if (keys.isEmpty() && deleted.isEmpty()) return raw
        val root = Json.parseToJsonElement(raw).jsonObject
        val legacy = (root["elements"] as? JsonArray).orEmpty().filter { value ->
            ((value as? JsonObject)?.get("id") as? JsonPrimitive)?.content.isNullOrBlank()
        }
        val orderedKeys = (root["elements"] as? JsonArray).orEmpty().mapNotNull {
            ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.content
        }.filter { it in keys && it !in deleted }.distinct() + (keys - deleted).sorted().filterNot { key ->
            (root["elements"] as? JsonArray).orEmpty().any { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.content == key }
        }
        val values = orderedKeys.mapNotNull { key ->
            val map = (read.get(elements.id, key).orElse(null) as? AmValue.Map)?.id ?: return@mapNotNull null
            JsonObject(read.keys(map).orElseThrow().associateWith { field ->
                Json.parseToJsonElement((read.get(map, field).orElseThrow() as AmValue.Str).value)
            })
        }
        return JsonObject(root + ("elements" to JsonArray(legacy + values))).toString()
    }

    /** Canvas metadata and board live in the same Automerge transaction; notebook items are untouched. */
    internal fun canvasDocument(id: DocumentId): CanvasDocument? = open(id)?.withDocument { document ->
        canvasFrom(document)
    }?.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

    private fun canvasFrom(read: Read): CanvasDocument? {
        val metadata = (read.get(ObjectId.ROOT, "canvasMetadata").orElse(null) as? AmValue.Str)?.value
            ?: return null
        val doc = Json.decodeFromString<CanvasDocument>(metadata)
        val board = Json.parseToJsonElement(boardFrom(read)).jsonObject
        val scene = JsonObject(board.filterKeys { it != "schema" }).toString()
        val base = (read.get(ObjectId.ROOT, "canvasSceneBase").orElse(null) as? AmValue.Str)?.value
        return doc.copy(sceneJson = if (base == "" && scene == "{\"elements\":[]}") "" else scene)
    }

    internal fun writeCanvas(id: DocumentId, doc: CanvasDocument, expectedRevision: Long?): Boolean {
        val handle = requireNotNull(open(id)) { "Unknown notebook document: $id" }
        return handle.withDocument { document ->
            document.startTransaction().use { tx ->
                val current = canvasFrom(tx)
                if (expectedRevision != null && current?.revision != expectedRevision) return@withDocument false
                if (current != null && current.id != doc.id) error("Notebook belongs to another canvas")
                val base = (tx.get(ObjectId.ROOT, "canvasSceneBase").orElse(null) as? AmValue.Str)?.value
                val incoming = sceneBoard(doc.sceneJson)
                if (base == null || current?.sceneJson != doc.sceneJson) {
                    if (base == null) writeBoard(tx, incoming.toString())
                    else mergeCanvasBoard(tx, sceneBoard(base), incoming)
                }
                tx.set(ObjectId.ROOT, "canvasMetadata", Json.encodeToString(CanvasDocument.serializer(), doc.copy(sceneJson = "")))
                tx.set(ObjectId.ROOT, "canvasSceneBase", doc.sceneJson)
                tx.set(ObjectId.ROOT, "title", doc.title)
                tx.commit()
                true
            }
        }.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun sceneBoard(scene: String): JsonObject {
        val root = if (scene.isBlank()) JsonObject(mapOf("elements" to JsonArray(emptyList())))
            else Json.parseToJsonElement(scene).jsonObject
        require(root["elements"] is JsonArray) { "Canvas scene needs elements" }
        return JsonObject(root + ("schema" to JsonPrimitive("notebook-board/1")))
    }

    private fun syncElementDiff(
        tx: Transaction,
        key: String,
        oldVal: JsonObject?,
        nextVal: JsonObject?,
    ) {
        if (oldVal == nextVal) return
        if (nextVal == null) {
            removeElement(tx, key)
        } else {
            putElement(tx, key, nextVal)
        }
    }

    private fun updateEnvelopeField(
        envelope: MutableMap<String, JsonElement>,
        key: String,
        baseVal: JsonElement?,
        incomingVal: JsonElement?,
    ) {
        if (baseVal == incomingVal) return
        if (incomingVal != null) {
            envelope[key] = incomingVal
        } else {
            envelope.remove(key)
        }
    }

    private fun mergeCanvasBoard(tx: Transaction, base: JsonObject, incoming: JsonObject) {
        val current = Json.parseToJsonElement(boardFrom(tx)).jsonObject
        val old = parseIncomingElements(base)
        val next = parseIncomingElements(incoming)
        // Only replace elements changed by this canvas write; unrelated notebook edits survive.
        for (key in (old.keys + next.keys)) {
            syncElementDiff(tx, key, old[key], next[key])
        }
        val envelope = current.toMutableMap()
        for (key in (base.keys + incoming.keys).filter { it != "elements" && it != "schema" }) {
            updateEnvelopeField(envelope, key, base[key], incoming[key])
        }
        tx.set(ObjectId.ROOT, "board", JsonObject(envelope).toString())
    }

    private fun putElement(tx: Transaction, key: String, value: JsonObject) {
        val elements = boardElements(tx)
        val map = (tx.get(elements, key).orElse(null) as? AmValue.Map)?.id ?: tx.set(elements, key, ObjectType.MAP)
        tx.keys(map).orElseThrow().filter { it !in value }.forEach { tx.delete(map, it) }
        value.forEach { (field, fieldValue) -> tx.set(map, field, fieldValue.toString()) }
        val tombstones = (tx.get(ObjectId.ROOT, "boardTombstones").orElse(null) as? AmValue.Map)
        if (tombstones != null && tx.get(tombstones.id, key).isPresent) tx.delete(tombstones.id, key)
    }

    private fun removeElement(tx: Transaction, key: String) {
        val elements = boardElements(tx)
        if (tx.get(elements, key).isPresent) tx.delete(elements, key)
        val tombstones = (tx.get(ObjectId.ROOT, "boardTombstones").orElse(null) as? AmValue.Map)?.id
            ?: tx.set(ObjectId.ROOT, "boardTombstones", ObjectType.MAP)
        tx.set(tombstones, key, true)
    }

    fun setBoard(id: DocumentId, boardJson: String) {
        require(Json.parseToJsonElement(boardJson).jsonObject["schema"]?.jsonPrimitive?.content == "notebook-board/1") {
            "Unsupported notebook board schema"
        }
        mutate(id) { tx ->
            writeBoard(tx, boardJson)
        }
    }

    fun read(id: DocumentId): NotebookDocument? = open(id)?.withDocument { document ->
        val title = (document.get(ObjectId.ROOT, "title").orElseThrow() as AmValue.Str).value
        val textId = (document.get(ObjectId.ROOT, "markdown").orElseThrow() as AmValue.Text).id
        val board = boardFrom(document)
        val itemsId = (document.get(ObjectId.ROOT, "items").orElseThrow() as AmValue.Map).id
        val items = document.keys(itemsId).orElseThrow().map { key ->
            val value = (document.get(itemsId, key).orElseThrow() as AmValue.Str).value
            Json.decodeFromString<NotebookItem>(value).also { require(it.id == key) }
        }
        NotebookDocument(NotebookDocumentId(id.stableKey()), title, document.text(textId).orElseThrow(), board, items)
    }?.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

    fun insertMarkdown(id: DocumentId, index: Int, text: String) {
        require(index >= 0)
        mutate(id) { tx ->
            val textId = (tx.get(ObjectId.ROOT, "markdown").orElseThrow() as AmValue.Text).id
            tx.spliceText(textId, index.toLong(), 0, text)
        }
    }

    fun putItem(id: DocumentId, item: NotebookItem) {
        require(item.id.isNotBlank())
        mutate(id) { tx ->
            val itemsId = (tx.get(ObjectId.ROOT, "items").orElseThrow() as AmValue.Map).id
            tx.set(itemsId, item.id, Json.encodeToString(NotebookItem.serializer(), item))
        }
    }

    private inline fun mutate(id: DocumentId, crossinline action: (org.automerge.Transaction) -> Unit) {
        val handle = requireNotNull(open(id)) { "Unknown notebook document: $id" }
        handle.withDocument { document ->
            document.startTransaction().use { tx ->
                action(tx)
                tx.commit()
            }
        }.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        stopPolling()
        poller.shutdownNow()
        repo.close()
    }

    private fun DocumentId.stableKey(): String = getBytes().joinToString("") { byte ->
        (byte.toInt() and 255).toString(16).padStart(2, '0')
    }

    private companion object {
        const val TIMEOUT_SECONDS = 10L
    }
}
