package com.letta.mobile.data.memory.graph

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The "New block" sheet: a label + initial value for a block under [agentId]. */
@Immutable
data class MemoryBlockDraft(
    val agentId: String,
    val label: String = "",
    val value: String = "",
    val isSaving: Boolean = false,
    val error: String? = null,
) {
    /** The label as it will be written (`memory/system/<label>.md`). */
    val normalizedLabel: String get() = label.trim()

    /** Why the label is not acceptable yet; null once it is a valid new block name. */
    val labelError: String? get() = MemoryBlockLabels.problem(normalizedLabel)

    val canSubmit: Boolean get() = !isSaving && labelError == null
}

/** A pending delete of [ref], awaiting the user's confirmation. */
@Immutable
data class MemoryBlockDeletion(
    val ref: MemoryBlockRef,
    val isDeleting: Boolean = false,
    val error: String? = null,
)

/** Create / delete actions on core-memory blocks (bfooy.5). */
interface MemoryBlockLifecycleActions {
    fun beginCreate()
    fun updateCreateLabel(text: String)
    fun updateCreateValue(text: String)
    fun cancelCreate()
    fun submitCreate()

    /** Opens the delete confirmation for the selected block. */
    fun requestDelete()
    fun cancelDelete()
    fun confirmDelete()
}

/** Label rules: one MemFS path segment, so the node's segment check never rejects it. */
object MemoryBlockLabels {
    private val VALID = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]*$")
    const val MAX_LENGTH: Int = 64

    fun problem(label: String): String? = when {
        label.isEmpty() -> "Enter a label"
        label.length > MAX_LENGTH -> "Keep the label under $MAX_LENGTH characters"
        !VALID.matches(label) -> "Use letters, digits, '-', '_' or '.', starting with a letter or digit"
        else -> null
    }
}

/**
 * The create/delete half of the memory page, kept apart from graph projection
 * and editing. Each commit goes through [MemoryBlockContentPort] (a committed
 * MemFS write/delete on the App Server) and then [onCommitted] reloads the
 * overview so the graph gains or loses the node.
 */
internal class MemoryBlockLifecycle(
    private val state: MutableStateFlow<MemoryPageState>,
    private val blocks: MemoryBlockContentPort,
    private val scope: CoroutineScope,
    private val onCommitted: () -> Unit,
    private val errorMessage: (Throwable) -> String,
) : MemoryBlockLifecycleActions {
    override fun beginCreate() {
        val current = state.value
        val agentId = current.parity.memory.selectedAgentId ?: return
        if (!current.canCreateBlock || current.creation != null) return
        state.update { it.copy(creation = MemoryBlockDraft(agentId)) }
    }

    override fun updateCreateLabel(text: String) = updateDraft { it.copy(label = text, error = null) }

    override fun updateCreateValue(text: String) = updateDraft { it.copy(value = text, error = null) }

    override fun cancelCreate() {
        if (state.value.creation?.isSaving == true) return
        state.update { it.copy(creation = null) }
    }

    override fun submitCreate() {
        val draft = state.value.creation ?: return
        if (!draft.canSubmit) return
        updateDraft { it.copy(isSaving = true, error = null) }
        launchCommit(
            work = { blocks.create(draft.agentId, draft.normalizedLabel, draft.value) },
            onSuccess = { it.copy(creation = null) },
            onFailed = { message -> updateDraft { it.copy(isSaving = false, error = message) } },
        )
    }

    override fun requestDelete() {
        val selection = state.value.selection ?: return
        val ref = selection.detail.blockRef ?: return
        if (!selection.canDelete || state.value.deletion != null) return
        state.update { it.copy(deletion = MemoryBlockDeletion(ref)) }
    }

    override fun cancelDelete() {
        if (state.value.deletion?.isDeleting == true) return
        state.update { it.copy(deletion = null) }
    }

    override fun confirmDelete() {
        val deletion = state.value.deletion ?: return
        if (deletion.isDeleting) return
        updateDeletion { it.copy(isDeleting = true, error = null) }
        launchCommit(
            work = { blocks.delete(deletion.ref) },
            onSuccess = { it.withoutDeleted(deletion.ref) },
            onFailed = { message -> updateDeletion { it.copy(isDeleting = false, error = message) } },
        )
    }

    /** Run one committing write; on success apply [onSuccess] and reload, else report the message. */
    private fun launchCommit(
        work: suspend () -> Unit,
        onSuccess: (MemoryPageState) -> MemoryPageState,
        onFailed: (String) -> Unit,
    ) {
        scope.launch {
            val failure = attempt { work() }.exceptionOrNull()
            if (failure != null) return@launch onFailed(errorMessage(failure))
            state.update(onSuccess)
            onCommitted()
        }
    }

    private fun MemoryPageState.withoutDeleted(ref: MemoryBlockRef): MemoryPageState = copy(
        deletion = null,
        selection = selection?.takeUnless { it.detail.blockRef == ref },
    )

    private fun updateDraft(transform: (MemoryBlockDraft) -> MemoryBlockDraft) {
        state.update { s -> s.creation?.let { s.copy(creation = transform(it)) } ?: s }
    }

    private fun updateDeletion(transform: (MemoryBlockDeletion) -> MemoryBlockDeletion) {
        state.update { s -> s.deletion?.let { s.copy(deletion = transform(it)) } ?: s }
    }
}
