package com.letta.mobile.data.canvas

import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** A notebook document is independent of any conversation or agent. */
@Serializable
data class NotebookDocumentId(val value: String) {
    companion object {
        @OptIn(ExperimentalUuidApi::class)
        fun generate(): NotebookDocumentId = NotebookDocumentId("notebook-${Uuid.random()}")
    }
}

/** Stable, versioned item references; unknown kinds and payloads round-trip unchanged. */
@Serializable
data class NotebookItem(
    val id: String,
    val kind: String,
    val version: Int = 1,
    val targetDocumentId: NotebookDocumentId? = null,
    val conversationId: String? = null,
    val payloadJson: String = "{}",
)

/** An item is data, never executable code merely because it was received from a peer. */
@Serializable
data class NotebookItemContract(
    val kind: String,
    val version: Int,
    val rendererId: String? = null,
    val editorId: String? = null,
    val agentToolId: String? = null,
    val executable: Boolean = false,
)

/** Per-peer grants are explicit; chat access is separately enforced by its App Server. */
@Serializable
data class NotebookPeerGrant(
    val peerId: String,
    val mayRead: Boolean = true,
    val mayEdit: Boolean = false,
    val mayExecutePlugins: Boolean = false,
) {
    init { require(peerId.isNotBlank()) }
}

fun mayExecuteNotebookItem(contract: NotebookItemContract?, grant: NotebookPeerGrant?): Boolean =
    contract?.executable == true && grant?.mayExecutePlugins == true && grant.mayEdit


@Serializable
data class NotebookDocument(
    val id: NotebookDocumentId,
    val title: String,
    val markdown: String = "",
    val sceneJson: String = "",
    val items: List<NotebookItem> = emptyList(),
)

/** Traverses references once per document so cycles and shared subgraphs cannot recurse forever. */
fun traverseNotebookDocuments(
    root: NotebookDocumentId,
    lookup: (NotebookDocumentId) -> NotebookDocument?,
): List<NotebookDocument> {
    val visited = mutableSetOf<NotebookDocumentId>()
    val pending = ArrayDeque<NotebookDocumentId>()
    val result = mutableListOf<NotebookDocument>()
    pending.addLast(root)
    while (pending.isNotEmpty()) {
        val id = pending.removeLast()
        if (!visited.add(id)) continue
        val document = lookup(id) ?: continue
        result.add(document)
        document.items.asReversed().forEach { item ->
            item.targetDocumentId?.let(pending::addLast)
        }
    }
    return result
}
