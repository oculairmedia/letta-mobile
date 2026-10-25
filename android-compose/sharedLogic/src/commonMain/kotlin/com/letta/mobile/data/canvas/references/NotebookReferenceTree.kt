package com.letta.mobile.data.canvas.references

import com.letta.mobile.data.canvas.NotebookDocumentId
import com.letta.mobile.data.canvas.NotebookItem
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Bounds for a single preview, including its root and all placeholders. Root depth is zero.
 * The final available node is reserved for a cutoff placeholder, even if it might be a leaf.
 * Hard ceilings keep caller configuration from allowing unsafe recursion or composition size.
 */
data class NotebookReferenceLimits(val maxDepth: Int = 8, val maxNodes: Int = 128) {
    init {
        require(maxDepth in 0..32)
        require(maxNodes in 1..512)
    }
}

/** Read-only paint data; no editor or executable item is mounted by a preview. */
sealed interface NotebookReferenceContent {
    /** A resolved document's title and Markdown lens over its existing identity. */
    data class Markdown(val title: String, val body: String) : NotebookReferenceContent

    /** Explicit reasons why a target or opaque item is not expanded. */
    enum class Placeholder : NotebookReferenceContent {
        CYCLE, DEPTH_LIMIT, NODE_LIMIT, DENIED, MISSING, UNAVAILABLE, UNSUPPORTED_ITEM, INVALID_REFERENCE,
    }
}

/**
 * Immutable preview branch. [path] includes this target and is independent of sibling branches;
 * repeated sibling IDs are allowed. [item] retains an existing source item, including unknown
 * kinds, versions and payloads, without interpreting or rewriting it. Root has no source item.
 */
data class NotebookReferenceNode(
    val targetId: NotebookDocumentId?,
    val path: PersistentList<NotebookDocumentId>,
    val content: NotebookReferenceContent,
    val children: PersistentList<NotebookReferenceNode> = persistentListOf(),
    val item: NotebookItem? = null,
)

/** Build one immutable, bounded tree sequentially, with no polling or per-node background work. */
internal suspend fun buildNotebookReferenceTree(
    root: NotebookDocumentId,
    resolver: NotebookReferenceResolver,
    limits: NotebookReferenceLimits,
): NotebookReferenceNode = ReferenceTreeBuilder(resolver, limits).build(root, persistentListOf(), null)

private class ReferenceTreeBuilder(
    private val resolver: NotebookReferenceResolver,
    private val limits: NotebookReferenceLimits,
) {
    private var used = 0

    suspend fun build(
        id: NotebookDocumentId?,
        ancestors: PersistentList<NotebookDocumentId>,
        item: NotebookItem?,
    ): NotebookReferenceNode {
        currentCoroutineContext().ensureActive()
        used++
        val path = if (id == null) ancestors else ancestors.adding(id)
        val placeholder = when {
            used == limits.maxNodes -> NotebookReferenceContent.Placeholder.NODE_LIMIT
            item != null && (item.kind != "reference" || item.version != 1) ->
                NotebookReferenceContent.Placeholder.UNSUPPORTED_ITEM
            id == null -> NotebookReferenceContent.Placeholder.INVALID_REFERENCE
            id in ancestors -> NotebookReferenceContent.Placeholder.CYCLE
            ancestors.size > limits.maxDepth -> NotebookReferenceContent.Placeholder.DEPTH_LIMIT
            else -> null
        }
        if (placeholder != null) return NotebookReferenceNode(id, path, placeholder, item = item)
        return resolve(requireNotNull(id), path, item)
    }

    private suspend fun resolve(
        id: NotebookDocumentId,
        path: PersistentList<NotebookDocumentId>,
        item: NotebookItem?,
    ): NotebookReferenceNode {
        val result = try {
            resolver.resolve(id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            NotebookReferenceLoad.Unavailable
        }
        currentCoroutineContext().ensureActive()
        return when (result) {
            is NotebookReferenceLoad.Found -> {
                val document = result.document
                var children = persistentListOf<NotebookReferenceNode>()
                var index = 0
                // Do not filter/map/copy the source list: stop inspecting items as soon as capped.
                while (used < limits.maxNodes && index < document.items.size) {
                    val child = document.items[index++]
                    children = children.adding(build(child.targetDocumentId, path, child))
                }
                NotebookReferenceNode(id, path, NotebookReferenceContent.Markdown(document.title, document.markdown), children, item)
            }
            NotebookReferenceLoad.Denied -> NotebookReferenceNode(id, path, NotebookReferenceContent.Placeholder.DENIED, item = item)
            NotebookReferenceLoad.Missing -> NotebookReferenceNode(id, path, NotebookReferenceContent.Placeholder.MISSING, item = item)
            NotebookReferenceLoad.Unavailable ->
                NotebookReferenceNode(id, path, NotebookReferenceContent.Placeholder.UNAVAILABLE, item = item)
        }
    }
}
