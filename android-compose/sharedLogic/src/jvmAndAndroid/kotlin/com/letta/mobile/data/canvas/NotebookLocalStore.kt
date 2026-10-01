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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
import org.automerge.repo.Storage
import org.automerge.repo.storage.FileSystemStorage

/** One peer's durable notebook documents, independent of App Server conversations. */
class NotebookLocalStore(
    directory: Path,
    peerId: String,
    budget: NotebookHistoryBudget = NotebookHistoryBudget(),
    /** Wraps the repository's file storage; tests use it to inject failures. */
    storage: (Storage) -> Storage = { it },
) : AutoCloseable, CanvasStorageHealth {
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
    fun listDocuments(): List<DocumentId> = health.awaitPrepared().let { readIndex() }.filterNot(health::isQuarantined).map { key ->
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
                if (previous != content.markdown) tx.spliceText(textId, 0, previous.length.toLong(), content.markdown)
                NotebookBoardStorage.setStringIfChanged(tx, ObjectId.ROOT, "title", content.title)
                writeBoard(tx, content.board)
                tx.commit()
                true
            }
        }.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private val health = NotebookStorageHealth(directory, budget) { readIndex() }

    /** Storage faults: logged at ERROR and kept here for the board to show. Nothing is thrown. */
    val faults: NotebookStorageFaults get() = health.faults
    override val storageFaults: StateFlow<List<CanvasStorageFault>> get() = health.faults.faults

    init {
        // An over-budget document is archived and restarted (on a background thread) before the
        // repository may open it, and one that cannot be is set aside, so it never loads either.
        health.prepare()
    }

    val repo: Repo = Repo.load(
        RepoConfig.builder()
            .storage(health.observe(storage(FileSystemStorage(directory))))
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
                tx.set(ObjectId.ROOT, "board", NotebookBoardStorage.EMPTY_BOARD)
                tx.set(ObjectId.ROOT, "boardElements", ObjectType.MAP)
                tx.set(ObjectId.ROOT, "boardTombstones", ObjectType.MAP)
                tx.set(ObjectId.ROOT, "items", ObjectType.MAP)
                tx.commit()
            }
        }.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        index(handle.documentId)
        return handle.documentId
    }

    fun open(id: DocumentId): DocHandle? {
        health.awaitPrepared()
        if (health.isQuarantined(id.stableKey())) return null
        return repo.find(id).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).orElse(null)
    }

    private fun remember(id: DocumentId, doc: CanvasDocument) = health.rememberCanvas(id.stableKey(), doc.id)

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
        return NotebookBoardStorage.isPristine(tx)
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
        mutate(id) { tx -> NotebookBoardStorage.putElement(tx, key, element) }
    }

    fun removeBoardElement(id: DocumentId, elementId: String) {
        require(elementId.isNotBlank())
        mutate(id) { tx -> NotebookBoardStorage.deleteElement(tx, elementId) }
    }

    internal fun deletedBoardElements(id: DocumentId): List<CanvasDeletedElement> =
        requireNotNull(open(id)).withDocument { document ->
            NotebookBoardStorage.deletedElements(document)
        }.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

    private fun writeBoard(tx: Transaction, boardJson: String) =
        NotebookBoardStorage.writeBoard(tx, Json.parseToJsonElement(boardJson).jsonObject)

    private fun boardFrom(read: Read): String = NotebookBoardStorage.boardJson(read)

    /** Canvas metadata and board live in the same Automerge transaction; notebook items are untouched. */
    internal fun canvasDocument(id: DocumentId): CanvasDocument? = open(id)?.withDocument { document ->
        canvasFrom(document)
    }?.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)?.also { remember(id, it) }

    /**
     * The canvas's identity and ownership without its board: [CanvasDocument.sceneJson] is not
     * built. Lookups by id or conversation match on this, so finding one canvas no longer
     * decodes every board in the repository.
     */
    internal fun canvasMetadata(id: DocumentId): CanvasDocument? = open(id)?.withDocument { document ->
        (document.get(ObjectId.ROOT, "canvasMetadata").orElse(null) as? AmValue.Str)?.value
    }?.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)?.let { Json.decodeFromString<CanvasDocument>(it) }?.also { remember(id, it) }

    private fun canvasFrom(read: Read): CanvasDocument? {
        val metadata = (read.get(ObjectId.ROOT, "canvasMetadata").orElse(null) as? AmValue.Str)?.value
            ?: return null
        val doc = Json.decodeFromString<CanvasDocument>(metadata)
        val board = Json.parseToJsonElement(boardFrom(read)).jsonObject
        val scene = JsonObject(board.filterKeys { it != "schema" }).toString()
        val blankBase = NotebookBoardStorage.baseIsBlank(read) == true
        return doc.copy(sceneJson = if (blankBase && scene == "{\"elements\":[]}") "" else scene)
    }

    /**
     * Write a canvas save. Only what differs from the canvas's previous save is written, so the
     * document's history grows with the edit, not with the size of the board.
     */
    internal fun writeCanvas(id: DocumentId, doc: CanvasDocument, expectedRevision: Long?): Boolean {
        val handle = requireNotNull(open(id)) { "Unknown notebook document: $id" }
        return handle.withDocument { document ->
            document.startTransaction().use { tx ->
                val current = canvasFrom(tx)
                if (expectedRevision != null && current?.revision != expectedRevision) return@withDocument false
                if (current != null && current.id != doc.id) error("Notebook belongs to another canvas")
                val base = NotebookBoardStorage.readSceneBase(tx)
                val incoming = NotebookBoardStorage.sceneBoard(doc.sceneJson)
                if (base == null || current?.sceneJson != doc.sceneJson) {
                    if (base == null) NotebookBoardStorage.writeBoard(tx, incoming)
                    else NotebookBoardStorage.mergeCanvasBoard(tx, base, incoming)
                }
                val metadata = Json.encodeToString(CanvasDocument.serializer(), doc.copy(sceneJson = ""))
                NotebookBoardStorage.setStringIfChanged(tx, ObjectId.ROOT, "canvasMetadata", metadata)
                NotebookBoardStorage.writeSceneBase(tx, NotebookBoardStorage.sceneBaseOf(doc.sceneJson))
                NotebookBoardStorage.setStringIfChanged(tx, ObjectId.ROOT, "title", doc.title)
                tx.commit()
                true
            }
        }.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).also { written ->
            if (written) {
                remember(id, doc)
                health.checkBudget(id.stableKey())
            }
        }
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
        health.close()
    }

    private fun DocumentId.stableKey(): String = getBytes().joinToString("") { byte ->
        (byte.toInt() and 255).toString(16).padStart(2, '0')
    }

    private companion object {
        const val TIMEOUT_SECONDS = 10L
    }
}
