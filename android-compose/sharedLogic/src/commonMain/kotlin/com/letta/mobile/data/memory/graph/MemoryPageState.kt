package com.letta.mobile.data.memory.graph

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.memory.MemoryGraphNodeKind
import com.letta.mobile.data.memory.MemoryParityControllerState

/** The full memory page: overview data, the filtered graph, its layout, and the open card. */
@Immutable
data class MemoryPageState(
    val parity: MemoryParityControllerState = MemoryParityControllerState(),
    val view: MemoryGraphView = MemoryGraphView(),
    val layout: MemoryGraphLayout = MemoryGraphLayout(),
    val selection: MemoryNodeSelection? = null,
    /** An agent is selected and the backend exposes committed block writes. */
    val canCreateBlock: Boolean = false,
    /** The open "New block" sheet, if any. */
    val creation: MemoryBlockDraft? = null,
    /** The pending delete confirmation, if any. */
    val deletion: MemoryBlockDeletion? = null,
) {
    val selectedNodeId: String? get() = selection?.detail?.nodeId
}

/** Async full-content state of the selected node's card. */
sealed interface MemoryNodeContent {
    /** Not a block: the card shows the overview detail only. */
    data object Static : MemoryNodeContent

    data object Loading : MemoryNodeContent

    data class Loaded(val value: String) : MemoryNodeContent

    data class Failed(val message: String) : MemoryNodeContent
}

@Immutable
data class MemoryNodeEditor(
    val original: String,
    val draft: String,
    val isSaving: Boolean = false,
    val error: String? = null,
) {
    val isDirty: Boolean get() = draft != original

    fun exceeds(limit: Int?): Boolean = limit != null && draft.length > limit
}

@Immutable
data class MemoryNodeSelection(
    val detail: MemoryNodeDetail,
    val content: MemoryNodeContent = MemoryNodeContent.Static,
    val editor: MemoryNodeEditor? = null,
    /** The backend exposes a committed agent-block write. */
    val writable: Boolean = false,
) {
    /** Text the card shows: the loaded full value, else the overview body. */
    val displayText: String
        get() = (content as? MemoryNodeContent.Loaded)?.value ?: detail.body

    val canEdit: Boolean
        get() = writable && !detail.readOnly && detail.blockRef != null && content is MemoryNodeContent.Loaded

    /** A writable, non-read-only block that is not mid-save. */
    val canDelete: Boolean
        get() = writable && !detail.readOnly && detail.blockRef != null && editor?.isSaving != true

    val canSave: Boolean
        get() = editor != null && !editor.isSaving && editor.isDirty && !editor.exceeds(detail.limit)
}

/** Everything the page can ask for. The controller implements it; UI calls it. */
interface MemoryPageActions : MemoryBlockLifecycleActions {
    fun refresh()
    fun selectAgent(agentId: String)
    fun toggleKind(kind: MemoryGraphNodeKind)
    fun selectNode(nodeId: String)
    fun clearSelection()
    fun retryContent()
    fun beginEdit()
    fun updateDraft(text: String)
    fun cancelEdit()
    fun saveEdit()
}

/** Pure selection/editor transitions, kept apart from the coroutine glue. */
internal object MemoryNodeSelections {
    fun open(detail: MemoryNodeDetail, writable: Boolean): MemoryNodeSelection = MemoryNodeSelection(
        detail = detail,
        content = if (detail.blockRef != null) MemoryNodeContent.Loading else MemoryNodeContent.Static,
        writable = writable,
    )

    /** Re-resolve after an overview reload; the card closes if its node vanished. */
    fun reconcile(selection: MemoryNodeSelection?, refreshed: MemoryNodeDetail?): MemoryNodeSelection? =
        if (selection == null || refreshed == null) null else selection.copy(detail = refreshed)

    fun beginEdit(selection: MemoryNodeSelection): MemoryNodeSelection {
        if (!selection.canEdit || selection.editor != null) return selection
        val value = selection.displayText
        return selection.copy(editor = MemoryNodeEditor(original = value, draft = value))
    }

    fun updateDraft(selection: MemoryNodeSelection, text: String): MemoryNodeSelection {
        val editor = selection.editor ?: return selection
        if (editor.isSaving) return selection
        return selection.copy(editor = editor.copy(draft = text, error = null))
    }

    fun saving(selection: MemoryNodeSelection): MemoryNodeSelection =
        selection.copy(editor = selection.editor?.copy(isSaving = true, error = null))

    fun saved(selection: MemoryNodeSelection, value: String): MemoryNodeSelection =
        selection.copy(content = MemoryNodeContent.Loaded(value), editor = null)

    fun saveFailed(selection: MemoryNodeSelection, message: String): MemoryNodeSelection =
        selection.copy(editor = selection.editor?.copy(isSaving = false, error = message))
}
