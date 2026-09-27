package com.letta.mobile.data.canvas

import java.nio.charset.StandardCharsets.UTF_8
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.automerge.AmValue
import org.automerge.ObjectId
import org.automerge.ObjectType
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

    private val projection = NotebookFilesystemProjection(directory.resolve("projection"))
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

    /** Reconcile only previously projected local documents, leaving unprojected docs untouched. */
    @Synchronized
    fun pollProjections(): Map<DocumentId, NotebookProjectionResult> = listDocuments()
        .filter { projection.hasBaseline(it) }
        .associateWith { reconcile(it) }

    /** Opt-in lifecycle polling; report conflicts to the caller rather than overwriting either side. */
    @Synchronized
    fun startPolling(intervalMillis: Long, onResult: (DocumentId, NotebookProjectionResult) -> Unit = { _, _ -> }) {
        require(intervalMillis > 0)
        check(!closed) { "Notebook store is closed" }
        check(polling == null) { "Projection polling already started" }
        polling = poller.scheduleWithFixedDelay({
            pollProjections().forEach { (id, result) -> onResult(id, result) }
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
    fun reconcile(id: DocumentId): NotebookProjectionResult = projection.reconcile(this, id)

    /** Write current notebook state to its filesystem representation. */
    @Synchronized
    fun project(id: DocumentId): NotebookProjectionResult = projection.project(this, id)

data class NotebookContent(
    val title: String,
    val markdown: String,
    val board: String,
)

    internal fun replaceProjection(id: DocumentId, content: NotebookContent) {
        mutate(id) { tx ->
            val textId = (tx.get(ObjectId.ROOT, "markdown").orElseThrow() as AmValue.Text).id
            val previous = tx.text(textId).orElseThrow()
            tx.spliceText(textId, 0, previous.length.toLong(), content.markdown)
            tx.set(ObjectId.ROOT, "title", content.title)
            tx.set(ObjectId.ROOT, "board", content.board)
        }
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
                tx.set(ObjectId.ROOT, "markdown", ObjectType.TEXT)
                tx.set(ObjectId.ROOT, "boardVersion", 1)
                tx.set(ObjectId.ROOT, "board", "{\"schema\":\"notebook-board/1\",\"elements\":[]}")
                tx.set(ObjectId.ROOT, "items", ObjectType.MAP)
                tx.commit()
            }
        }.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        index(handle.documentId)
        return handle.documentId
    }

    fun open(id: DocumentId): DocHandle? =
        repo.find(id).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).orElse(null)

    fun setBoard(id: DocumentId, boardJson: String) {
        require(Json.parseToJsonElement(boardJson).jsonObject["schema"]?.jsonPrimitive?.content == "notebook-board/1") {
            "Unsupported notebook board schema"
        }
        mutate(id) { tx ->
            tx.set(ObjectId.ROOT, "board", boardJson)
        }
    }

    fun read(id: DocumentId): NotebookDocument? = open(id)?.withDocument { document ->
        val title = (document.get(ObjectId.ROOT, "title").orElseThrow() as AmValue.Str).value
        val textId = (document.get(ObjectId.ROOT, "markdown").orElseThrow() as AmValue.Text).id
        val board = (document.get(ObjectId.ROOT, "board").orElseThrow() as AmValue.Str).value
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
