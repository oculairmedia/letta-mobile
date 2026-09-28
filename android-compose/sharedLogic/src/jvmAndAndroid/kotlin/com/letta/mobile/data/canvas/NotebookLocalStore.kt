package com.letta.mobile.data.canvas

import java.nio.file.Path
import java.util.concurrent.TimeUnit
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
    private val projection = NotebookFilesystemProjection(directory.resolve("projection"))

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

    override fun close() = repo.close()

    private fun DocumentId.stableKey(): String = getBytes().joinToString("") { byte ->
        (byte.toInt() and 255).toString(16).padStart(2, '0')
    }

    private companion object {
        const val TIMEOUT_SECONDS = 10L
    }
}
