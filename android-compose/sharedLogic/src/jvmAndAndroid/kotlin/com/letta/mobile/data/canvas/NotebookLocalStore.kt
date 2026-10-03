package com.letta.mobile.data.canvas

import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.automerge.AmValue
import org.automerge.Document
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

/**
 * One peer's durable notebook documents, independent of App Server conversations.
 *
 * When the store opens, before anything can be opened, a document whose history is over [budget]
 * is archived and its board moved to a new document (new id, same canvas metadata), and the old id
 * is retired: it is never opened, indexed or stored again, whichever peer offers it. Canvas lookups
 * go by canvas id or conversation, so they find the new document. See [NotebookHistoryArchive].
 */
class NotebookLocalStore(
    directory: Path,
    peerId: String,
    private val budget: NotebookHistoryBudget = NotebookHistoryBudget(),
    /** Wraps the repository's file storage; tests use it to inject failures. */
    storage: (Storage) -> Storage = { it },
) : AutoCloseable, CanvasStorageHealth {
    private val canvasLockFile = directory.resolve("notebook-canvas.lock")
    private val projection = NotebookFilesystemProjection(directory.resolve("projection"))

    /**
     * Serialize canvas claims, CAS and history moves across instances and processes using this
     * repository. Waits for this store's own startup moves first, which run under the same lock.
     */
    internal fun <T> withCanvasLock(action: () -> T): T {
        health.awaitPrepared()
        return fileLock(action)
    }

    private fun <T> fileLock(action: () -> T): T {
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
    private val documents = NotebookDocumentIndex(directory)
    private val poller = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "notebook-projection-poller").apply { isDaemon = true }
    }
    private var polling: ScheduledFuture<*>? = null
    private var closed = false

    /** Indexed notebooks include local creations and explicitly registered remote documents. */
    @Synchronized
    fun listDocuments(): List<DocumentId> = health.awaitPrepared().let { documents.read() }
        .filterNot { health.isQuarantined(it) || health.isRetired(it) }.map { key ->
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

    /**
     * Register a known remote document after it is available in this repository. A retired
     * document (moved to a new one here) is never registered again, whoever offers it; returns
     * whether [id] is indexed.
     */
    @Synchronized
    fun registerDocument(id: DocumentId): Boolean {
        check(!closed) { "Notebook store is closed" }
        health.awaitPrepared()
        if (health.isRetired(id.stableKey())) {
            Telemetry.event(
                NotebookStorageFaults.TAG, "retired_document_refused",
                "documentId" to id.stableKey(), "reason" to "moved to a new document; not registered again",
                level = Telemetry.Level.WARN,
            )
            return false
        }
        requireNotNull(open(id)) { "Unknown notebook document: $id" }
        documents.add(id.stableKey())
        return true
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
        return handle.writing(id) { document ->
            document.startTransaction().use { tx ->
                val textId = (tx.get(ObjectId.ROOT, "markdown").orElseThrow() as AmValue.Text).id
                val previous = tx.text(textId).orElseThrow()
                val currentTitle = (tx.get(ObjectId.ROOT, "title").orElseThrow() as AmValue.Str).value
                val currentBoard = boardFrom(tx)
                if (previous != expected.markdown) return@writing false
                if (currentTitle != expected.title) return@writing false
                if (currentBoard != expected.sceneJson) return@writing false
                if (previous != content.markdown) tx.spliceText(textId, 0, previous.length.toLong(), content.markdown)
                NotebookBoardStorage.setStringIfChanged(tx, ObjectId.ROOT, "title", content.title)
                writeBoard(tx, content.board)
                tx.commit()
                true
            }
        }
    }

    private val health = NotebookStorageHealth(directory, budget, documents, { action -> fileLock(action) })

    /**
     * Storage faults: logged at ERROR and kept here for the board to show. Nothing is thrown,
     * except [NotebookReadOnlyException] from a write the store refuses.
     */
    val faults: NotebookStorageFaults get() = health.faults
    override val storageFaults: StateFlow<List<CanvasStorageFault>> get() = health.faults.faults

    /** Whether writes are refused after a repository error; see [CanvasStorageFault.Kind.READ_ONLY]. */
    val isReadOnly: Boolean get() = health.isReadOnly

    init {
        // On a background thread, before the repository may open anything: finish interrupted
        // moves, check every document against the budget, and move over-budget ones to new
        // documents (or set aside those that cannot be). Moving only here, before any open, means
        // no canvas session is ever bound to a document that is then retired under it.
        health.prepare()
    }

    /** The Automerge repository, for this store's own reads and writes; peer sync uses [repoForSync]. */
    internal val repo: Repo = Repo.load(
        RepoConfig.builder()
            .storage(health.observe(storage(FileSystemStorage(directory))))
            .peerId(PeerId.fromString(peerId))
            // Retired documents are never offered to peers; their storage is filtered out too.
            .announcePolicy { id, _ -> CompletableFuture.completedFuture(!health.isRetired(id.stableKey())) }
            .build(),
    ).also(health::attach)

    /**
     * The repository, for peer sync, once the store's startup moves are done. A peer may still
     * offer a retired document: it is neither stored nor indexed (see [NotebookDocumentIndex]).
     */
    fun repoForSync(): Repo {
        health.awaitPrepared()
        return repo
    }

    /** Retired documents a peer offered since the store opened; each was refused. */
    internal fun retiredDocumentsOffered(): Set<String> = health.retiredOffered()

    /**
     * Note every document whose board layout this build cannot write, so each shows a READ_ONLY
     * fault now rather than on its first refused write. Returns their ids.
     */
    fun checkLayouts(): List<DocumentId> = listDocuments().filter { id ->
        runCatching {
            open(id)?.withDocument { document -> NotebookBoardStorage.layoutVersion(document) }?.await()
        }.getOrNull()?.let { version -> noteLayout(id, version) } == false
    }

    /** True if this build can write [version]; otherwise records the refusal. */
    private fun noteLayout(id: DocumentId, version: Long): Boolean {
        if (version <= NotebookBoardStorage.LAYOUT_VERSION) return true
        health.refuseLayout(id.stableKey(), version)
        return false
    }

    private class Written<T>(val value: T)

    /**
     * Run a write on [id]'s document, refused (with [NotebookReadOnlyException], outside the
     * repository's callback) while the store is read-only or the board's layout is newer than this
     * build's. Nothing is thrown inside the callback: an exception escaping a `withDocument` block
     * leaves the repository's copy of the document unusable.
     */
    private fun <T> DocHandle.writing(id: DocumentId, block: (Document) -> T): T {
        health.checkWritable()
        var newerLayout = 0L
        val written = withDocument { document ->
            val version = NotebookBoardStorage.layoutVersion(document)
            if (version > NotebookBoardStorage.LAYOUT_VERSION) {
                newerLayout = version
                null
            } else {
                Written(block(document))
            }
        }.await()
        if (written == null) throw health.refuseLayout(id.stableKey(), newerLayout)
        return written.value
    }

    /** Await a repository future, surfacing a refused write as itself rather than wrapped. */
    private fun <T> CompletableFuture<T>.await(): T = try {
        get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    } catch (error: ExecutionException) {
        throw (error.cause as? NotebookReadOnlyException) ?: error
    }

    @Synchronized
    fun create(title: String): DocumentId {
        health.checkWritable()
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
        documents.add(handle.documentId.stableKey())
        return handle.documentId
    }

    fun open(id: DocumentId): DocHandle? {
        health.awaitPrepared()
        if (health.isQuarantined(id.stableKey()) || health.isRetired(id.stableKey())) return null
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
        return handle.writing(id) { document ->
            document.startTransaction().use { tx ->
                val marker = tx.get(ObjectId.ROOT, "importedCanvasId").orElse(null)
                if (marker != null) {
                    return@writing if (checkAlreadyImported(tx, marker, importData)) {
                        NotebookCanvasImportResult.ALREADY_IMPORTED
                    } else {
                        NotebookCanvasImportResult.CONFLICT
                    }
                }
                if (!isPristineDocument(tx)) {
                    return@writing NotebookCanvasImportResult.CONFLICT
                }
                tx.set(ObjectId.ROOT, "title", importData.title)
                writeBoard(tx, importData.board)
                tx.set(ObjectId.ROOT, "importedCanvasId", importData.sourceId)
                tx.set(ObjectId.ROOT, "importedBoard", importData.board)
                tx.set(ObjectId.ROOT, "importedTitle", importData.title)
                tx.commit()
                NotebookCanvasImportResult.IMPORTED
            }
        }
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
        canvasFrom(document) to NotebookBoardStorage.layoutVersion(document)
    }?.await()?.let { (doc, layout) ->
        doc?.let { remember(id, it) }
        // A board this build cannot write still opens; it says why its edits are not saved.
        noteLayout(id, layout)
        doc
    }

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
        // Parsed before entering the repository's callback, which must not throw.
        val write = CanvasWrite(doc, expectedRevision)
        val result = handle.writing(id) { document ->
            document.startTransaction().use { tx -> applyCanvasWrite(tx, write) }
        }
        return checkNotNull(result) { "Notebook belongs to another canvas" }.also { written ->
            if (written) {
                remember(id, doc)
                health.checkBudget(id.stableKey())
            }
        }
    }

    /** A canvas write, its scene parsed up front. */
    private class CanvasWrite(val doc: CanvasDocument, private val expectedRevision: Long?) {
        val incoming = NotebookBoardStorage.sceneBoard(doc.sceneJson)
        val base = NotebookBoardStorage.sceneBaseOf(doc.sceneJson)

        /** Whether [current] is at the revision this write expects, if it expects one. */
        fun expects(current: CanvasDocument?): Boolean = expectedRevision == null || current?.revision == expectedRevision
    }

    /** [write] in [tx]: false on a stale revision, null if the notebook holds another canvas. */
    private fun applyCanvasWrite(tx: Transaction, write: CanvasWrite): Boolean? {
        val doc = write.doc
        val current = canvasFrom(tx)
        if (!write.expects(current)) return false
        if (current != null && current.id != doc.id) return null
        val base = NotebookBoardStorage.readSceneBase(tx)
        when {
            base == null -> NotebookBoardStorage.writeBoard(tx, write.incoming)
            current?.sceneJson != doc.sceneJson -> NotebookBoardStorage.mergeCanvasBoard(tx, base, write.incoming)
        }
        val metadata = Json.encodeToString(CanvasDocument.serializer(), doc.copy(sceneJson = ""))
        NotebookBoardStorage.setStringIfChanged(tx, ObjectId.ROOT, "canvasMetadata", metadata)
        NotebookBoardStorage.writeSceneBase(tx, write.base)
        NotebookBoardStorage.setStringIfChanged(tx, ObjectId.ROOT, "title", doc.title)
        tx.commit()
        return true
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
        handle.writing(id) { document ->
            document.startTransaction().use { tx ->
                action(tx)
                tx.commit()
            }
        }
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
