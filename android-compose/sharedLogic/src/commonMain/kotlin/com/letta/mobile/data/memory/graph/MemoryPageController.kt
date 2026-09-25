package com.letta.mobile.data.memory.graph

import com.letta.mobile.data.memory.MemoryGraphNodeKind
import com.letta.mobile.data.memory.MemoryParityController
import com.letta.mobile.data.memory.MemoryParityControllerState
import com.letta.mobile.data.memory.MemoryParitySource
import com.letta.mobile.data.session.SessionRepositoryGraph
import com.letta.mobile.data.session.SessionRepositoryGraphProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Platform-neutral controller behind the shared memory page (Android, desktop,
 * and any future host). It projects the overview ([MemoryParitySource]) into a
 * filtered graph with a deterministic layout, tracks the selected node's card,
 * and runs the load → edit → committed-save flow through [MemoryBlockContentPort].
 */
class MemoryPageController private constructor(
    private val source: MemoryParitySource,
    private val blocks: MemoryBlockContentPort,
    private val scope: CoroutineScope,
    private val layoutDispatcher: CoroutineDispatcher,
    private val editErrorMessage: (Throwable) -> String,
    private val stateFlow: MutableStateFlow<MemoryPageState>,
) : MemoryPageActions,
    MemoryBlockLifecycleActions by MemoryBlockLifecycle(stateFlow, blocks, scope, source::reload, editErrorMessage),
    AutoCloseable {
    constructor(
        source: MemoryParitySource,
        blocks: MemoryBlockContentPort,
        scope: CoroutineScope,
        layoutDispatcher: CoroutineDispatcher = Dispatchers.Default,
        editErrorMessage: (Throwable) -> String = ::memoryEditErrorMessage,
    ) : this(source, blocks, scope, layoutDispatcher, editErrorMessage, MutableStateFlow(MemoryPageState()))

    val state: StateFlow<MemoryPageState> = stateFlow.asStateFlow()
    private val disabledKinds = MutableStateFlow<Set<MemoryGraphNodeKind>>(emptySet())
    private var projectionJob: Job? = null
    private var contentJob: Job? = null
    private var saveJob: Job? = null

    fun start() {
        ensureProjecting()
        source.start()
    }

    override fun refresh() {
        ensureProjecting()
        source.reload()
    }

    override fun selectAgent(agentId: String) {
        ensureProjecting()
        clearSelection()
        source.selectAgent(agentId)
    }

    override fun toggleKind(kind: MemoryGraphNodeKind) {
        disabledKinds.update { MemoryGraphViews.toggle(it, stateFlow.value.view.kindsPresent, kind) }
    }

    override fun selectNode(nodeId: String) {
        if (stateFlow.value.selectedNodeId == nodeId) return
        val detail = MemoryNodeDetails.resolve(stateFlow.value.parity.memory, nodeId) ?: return
        cancelNodeJobs()
        stateFlow.update { it.copy(selection = MemoryNodeSelections.open(detail, canWrite())) }
        detail.blockRef?.let { loadContent(nodeId, it) }
    }

    /** Re-fetch the open card's block after a failed load. */
    override fun retryContent() {
        val selection = stateFlow.value.selection ?: return
        val ref = selection.detail.blockRef ?: return
        updateSelection(selection.detail.nodeId) { it.copy(content = MemoryNodeContent.Loading) }
        loadContent(selection.detail.nodeId, ref)
    }

    override fun clearSelection() {
        cancelNodeJobs()
        stateFlow.update { it.copy(selection = null) }
    }

    override fun beginEdit() = updateOpenSelection(MemoryNodeSelections::beginEdit)

    override fun updateDraft(text: String) = updateOpenSelection { MemoryNodeSelections.updateDraft(it, text) }

    override fun cancelEdit() {
        if (stateFlow.value.selection?.editor?.isSaving == true) return
        updateOpenSelection { it.copy(editor = null) }
    }

    override fun saveEdit() {
        val selection = stateFlow.value.selection ?: return
        val ref = selection.detail.blockRef ?: return
        val draft = selection.editor?.draft ?: return
        if (!selection.canSave) return
        val nodeId = selection.detail.nodeId
        updateSelection(nodeId, MemoryNodeSelections::saving)
        saveJob = scope.launch { persist(nodeId, ref, draft) }
    }

    override fun close() {
        cancelNodeJobs()
        projectionJob?.cancel()
        source.close()
    }

    private suspend fun persist(nodeId: String, ref: MemoryBlockRef, draft: String) {
        val failure = attempt { blocks.save(ref, draft) }.exceptionOrNull()
        if (failure == null) {
            updateSelection(nodeId) { MemoryNodeSelections.saved(it, draft) }
            // The overview preview and char counts derive from the committed value.
            source.reload()
        } else {
            updateSelection(nodeId) { MemoryNodeSelections.saveFailed(it, editErrorMessage(failure)) }
        }
    }

    private fun loadContent(nodeId: String, ref: MemoryBlockRef) {
        contentJob?.cancel()
        contentJob = scope.launch {
            val content = attempt { blocks.load(ref) }.fold(
                onSuccess = { MemoryNodeContent.Loaded(it.value) },
                onFailure = { MemoryNodeContent.Failed(editErrorMessage(it)) },
            )
            updateSelection(nodeId) { it.copy(content = content) }
        }
    }

    private fun ensureProjecting() {
        if (projectionJob?.isActive == true) return
        projectionJob = scope.launch {
            combine(source.state, disabledKinds, ::Pair).collectLatest { (parity, disabled) ->
                project(parity, disabled)
            }
        }
    }

    private suspend fun project(parity: MemoryParityControllerState, disabled: Set<MemoryGraphNodeKind>) {
        val view = MemoryGraphViews.build(parity.memory.graph, disabled)
        val current = stateFlow.value
        val layout = if (view.sameTopologyAs(current.view) && !current.layout.isEmpty) {
            current.layout
        } else {
            withContext(layoutDispatcher) { MemoryGraphLayoutEngine.layout(view) }
        }
        stateFlow.update { state ->
            state.copy(
                parity = parity,
                view = view,
                layout = layout,
                selection = state.selection?.let { reconcile(it, parity, view) },
                canCreateBlock = parity.memory.selectedAgentId != null && canWrite(),
            )
        }
    }

    private fun reconcile(
        selection: MemoryNodeSelection,
        parity: MemoryParityControllerState,
        view: MemoryGraphView,
    ): MemoryNodeSelection? {
        val nodeId = selection.detail.nodeId
        val refreshed = MemoryNodeDetails.resolve(parity.memory, nodeId)?.takeIf { view.node(nodeId) != null }
        return MemoryNodeSelections.reconcile(selection, refreshed)
    }

    private fun updateOpenSelection(transform: (MemoryNodeSelection) -> MemoryNodeSelection) {
        val nodeId = stateFlow.value.selectedNodeId ?: return
        updateSelection(nodeId, transform)
    }

    private fun updateSelection(nodeId: String, transform: (MemoryNodeSelection) -> MemoryNodeSelection) {
        stateFlow.update { state ->
            val selection = state.selection
            if (selection?.detail?.nodeId != nodeId) state else state.copy(selection = transform(selection))
        }
    }

    private fun canWrite(): Boolean = runCatching { blocks.canWrite() }.getOrDefault(false)

    private fun cancelNodeJobs() {
        contentJob?.cancel()
        saveJob?.cancel()
    }

    companion object {
        /** Standard wiring: the session graph's overview plus its block repository. */
        fun <Graph : SessionRepositoryGraph> forSession(
            sessionGraphProvider: SessionRepositoryGraphProvider<Graph>,
            scope: CoroutineScope,
            errorMessageMapper: (Throwable) -> String,
        ): MemoryPageController = MemoryPageController(
            source = MemoryParityController(
                sessionGraphProvider = sessionGraphProvider,
                scope = scope,
                errorMessageMapper = errorMessageMapper,
            ),
            blocks = SessionGraphMemoryBlockPort(sessionGraphProvider),
            scope = scope,
        )
    }
}

internal suspend fun <T> attempt(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (t: Throwable) {
        Result.failure(t)
    }

internal fun memoryEditErrorMessage(throwable: Throwable): String =
    throwable.message?.takeIf { it.isNotBlank() } ?: when (throwable) {
        is UnsupportedOperationException -> "Memory blocks cannot be edited on this backend."
        is NoSuchElementException -> "Memory block was not found."
        else -> "Memory block could not be saved."
    }
