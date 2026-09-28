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
    val pluginId: String? = null,
)

/** Per-peer grants are explicit; chat access is separately enforced by its App Server. */
@Serializable
data class NotebookPeerGrant(
    val peerId: String,
    val mayRead: Boolean = true,
    val mayEdit: Boolean = false,
    val mayExecutePlugins: Boolean = false,
    val mayReference: Boolean = false,
    val mayUseAgentTools: Boolean = false,
) {
    init { require(peerId.isNotBlank()) }
}

/** Reference is a separate capability: neither reading nor editing implies it. */
fun mayReadNotebook(grant: NotebookPeerGrant?): Boolean = grant?.mayRead == true
fun mayEditNotebook(grant: NotebookPeerGrant?): Boolean = grant?.mayEdit == true
fun mayReferenceNotebook(grant: NotebookPeerGrant?): Boolean = grant?.mayReference == true

/** A locally installed plugin must be trusted for the exact kind and version it handles. */
@Serializable
data class NotebookInstalledPlugin(
    val pluginId: String,
    val kind: String,
    val version: Int,
    val trusted: Boolean = false,
)

/** This legacy grant check does not establish installation or trust; do not use it to run code. */
fun mayExecuteNotebookItem(contract: NotebookItemContract?, grant: NotebookPeerGrant?): Boolean =
    contract?.executable == true && grant?.mayExecutePlugins == true && grant.mayEdit

fun mayRunNotebookPlugin(
    item: NotebookItem,
    contract: NotebookItemContract?,
    grant: NotebookPeerGrant?,
    installedPlugin: NotebookInstalledPlugin?,
): Boolean =
    mayExecuteNotebookItem(contract, grant) &&
        contract?.kind == item.kind && contract.version == item.version &&
        !contract.pluginId.isNullOrBlank() &&
        installedPlugin?.trusted == true && installedPlugin.pluginId == contract.pluginId &&
        installedPlugin.kind == item.kind && installedPlugin.version == item.version

/** Agent-tool authorization is independent of plugin execution and document edit grants. */
fun mayUseNotebookAgentTool(
    item: NotebookItem,
    contract: NotebookItemContract?,
    grant: NotebookPeerGrant?,
    authorizedToolIds: Set<String>,
): Boolean =
    grant?.mayUseAgentTools == true && contract?.kind == item.kind &&
        contract.version == item.version && !contract.agentToolId.isNullOrBlank() &&
        contract.agentToolId in authorizedToolIds


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
