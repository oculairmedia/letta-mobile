package com.letta.mobile.data.canvas

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runtime session managing a [CanvasDocument] with multi-writer op-log support.
 *
 * Owns revision sequencing, in-memory state projection, op log append, and persistence synchronization.
 * Pure Kotlin Multiplatform domain logic in :sharedLogic, decoupled from any Compose UI.
 */
class CanvasSession(
    val canvasId: CanvasId,
    private val store: CanvasDocumentStore,
    val opLog: CanvasOpLog = InMemoryCanvasOpLog(),
    val syncTransport: CanvasSyncTransport? = null,
    private val clock: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) {
    private val mutex = Mutex()
    private val _document = MutableStateFlow<CanvasDocument?>(null)
    val document: StateFlow<CanvasDocument?> = _document.asStateFlow()

    private val _checkpoints = MutableStateFlow<List<CanvasCheckpoint>>(emptyList())
    val checkpoints: StateFlow<List<CanvasCheckpoint>> = _checkpoints.asStateFlow()

    private var lamportClock: Long = 0L

    private suspend fun currentDoc(): CanvasDocument =
        _document.value ?: store.get(canvasId) ?: CanvasDocument(
            id = canvasId,
            title = "Untitled Canvas",
            revision = 0L,
            sceneJson = "",
            updatedAtEpochMs = clock(),
        )

    private fun recordCheckpoint(
        doc: CanvasDocument,
        metadata: CanvasCommitMetadata = CanvasCommitMetadata(actorId = "system"),
    ) {
        val checkpoint = CanvasCheckpoint(
            checkpointId = "cp-${doc.revision}-${clock()}",
            canvasId = doc.id,
            revision = doc.revision,
            lamport = lamportClock,
            sceneJson = doc.sceneJson,
            actorId = metadata.actorId,
            description = metadata.description,
            createdAtEpochMs = doc.updatedAtEpochMs,
        )
        val currentList = _checkpoints.value
        _checkpoints.value = (listOf(checkpoint) + currentList).take(MAX_CHECKPOINTS)
    }

    private suspend fun commitUpdate(
        updated: CanvasDocument,
        metadata: CanvasCommitMetadata = CanvasCommitMetadata(),
    ): CanvasDocument {
        store.upsert(updated)
        _document.value = updated
        recordCheckpoint(updated, metadata = metadata)
        return updated
    }

    private suspend fun commitScene(
        sceneJson: String,
        metadata: CanvasCommitMetadata = CanvasCommitMetadata(),
    ): CanvasDocument {
        val current = currentDoc()
        return commitUpdate(
            current.copy(
                revision = current.revision + 1L,
                sceneJson = sceneJson,
                updatedAtEpochMs = clock(),
            ),
            metadata = metadata,
        )
    }

    /**
     * Loads the document from [store] into memory and updates [document] state.
     */
    suspend fun load(): CanvasDocument? = mutex.withLock {
        val loaded = store.get(canvasId)
        _document.value = loaded
        if (loaded != null && _checkpoints.value.isEmpty()) {
            recordCheckpoint(
                loaded,
                metadata = CanvasCommitMetadata(
                    actorId = loaded.agentId ?: "initial",
                    description = "Initial state",
                ),
            )
        }
        loaded
    }

    /**
     * Current scene JSON or empty string if uninitialized.
     */
    fun sceneJsonOrEmpty(): String = _document.value?.sceneJson.orEmpty()

    /**
     * Persists an updated scene JSON, incrementing revision and updating timestamp.
     *
     * An empty [sceneJson] is explicitly allowed (e.g. canvas cleared).
     */
    suspend fun saveScene(sceneJson: String): CanvasDocument = mutex.withLock {
        commitScene(sceneJson)
    }

    /**
     * Applies an agent-driven scene replacement, incrementing revision and updating timestamp.
     */
    suspend fun applyAgentReplace(sceneJson: String, actorId: String? = null): CanvasDocument = mutex.withLock {
        val current = currentDoc()
        val effectiveActor = actorId ?: current.agentId ?: "agent"
        if (current.acl != null && !current.acl.canWrite(effectiveActor)) {
            throw UnauthorizedCanvasMutationException(effectiveActor, canvasId)
        }
        commitScene(sceneJson)
    }

    /**
     * Applies a single locally-generated [CanvasOp], appends to [opLog], projects state,
     * updates persistence, and publishes to [syncTransport].
     */
    suspend fun applyLocal(op: CanvasOp): CanvasDocument = mutex.withLock {
        val current = currentDoc()
        if (current.acl != null && !current.acl.canWrite(op.actorId)) {
            throw UnauthorizedCanvasMutationException(op.actorId, canvasId)
        }
        opLog.append(canvasId, op)
        if (op.lamport > lamportClock) lamportClock = op.lamport
        val newScene = CanvasOpProjector.project(current.sceneJson, listOf(op))
        val updated = commitScene(newScene)
        syncTransport?.publish(canvasId, op)
        updated
    }

    /**
     * Applies a remote [CanvasOp], ignoring if already present in [opLog] (deduplication),
     * appends to [opLog], projects state, and updates persistence.
     */
    suspend fun applyRemote(op: CanvasOp): CanvasDocument? = mutex.withLock {
        val current = currentDoc()
        if (current.acl != null && !current.acl.canWrite(op.actorId)) {
            return null
        }
        if (opLog.has(canvasId, op.opId)) return null
        opLog.append(canvasId, op)
        if (op.lamport > lamportClock) lamportClock = op.lamport
        val newScene = CanvasOpProjector.project(current.sceneJson, listOf(op))
        commitScene(newScene)
    }

    private fun updateLamport(op: CanvasOp) {
        if (op.lamport > lamportClock) {
            lamportClock = op.lamport
        }
    }

    private suspend fun recordSingleOp(op: CanvasOp) {
        opLog.append(canvasId, op)
        updateLamport(op)
    }

    private suspend fun filterAndRecordOps(
        ops: List<CanvasOp>,
        isRemote: Boolean,
        acl: CanvasAcl?,
    ): List<CanvasOp> {
        val opsToApply = mutableListOf<CanvasOp>()
        for (op in ops) {
            if (shouldSkipOrReject(op, isRemote, acl)) continue
            recordSingleOp(op)
            opsToApply.add(op)
        }
        return opsToApply
    }

    private suspend fun shouldSkipOrReject(op: CanvasOp, isRemote: Boolean, acl: CanvasAcl?): Boolean {
        if (acl != null && !acl.canWrite(op.actorId)) {
            if (isRemote) return true
            throw UnauthorizedCanvasMutationException(op.actorId, canvasId)
        }
        return isRemote && opLog.has(canvasId, op.opId)
    }

    private suspend fun broadcastOps(ops: List<CanvasOp>) {
        val transport = syncTransport ?: return
        for (op in ops) {
            transport.publish(canvasId, op)
        }
    }

    /**
     * Applies a sequence of operations as a single revision bump.
     */
    suspend fun applyOps(ops: List<CanvasOp>, isRemote: Boolean = false): CanvasDocument = mutex.withLock {
        val current = currentDoc()
        val opsToApply = filterAndRecordOps(ops, isRemote, current.acl)
        if (opsToApply.isEmpty()) return current

        val newScene = CanvasOpProjector.project(current.sceneJson, opsToApply)
        val updated = commitScene(newScene)
        if (!isRemote) {
            broadcastOps(opsToApply)
        }
        updated
    }

    /**
     * Diffs [newJson] against current scene and applies the resulting operations locally.
     */
    /** The canvas's block documents as of the current scene. */
    fun documents(): List<CanvasSceneDocument> = CanvasOpProjector.documentsOf(sceneJsonOrEmpty())

    /** Writes a block document (Cascade JSON) as a local op; a no-op when the JSON is unchanged. */
    suspend fun setDocument(
        documentId: String,
        documentJson: String,
        actorId: String = "local_user",
    ): CanvasDocument? {
        if (documents().firstOrNull { it.id == documentId }?.json == documentJson) return null
        return applyLocal(
            CanvasOp.SetDocumentOp(
                opId = CanvasOpDiffer.generateOpId("doc"),
                actorId = actorId,
                lamport = lamportClock + 1,
                documentId = documentId,
                documentJson = documentJson,
            ),
        )
    }

    suspend fun removeDocument(documentId: String, actorId: String = "local_user"): CanvasDocument =
        applyLocal(
            CanvasOp.RemoveDocumentOp(
                opId = CanvasOpDiffer.generateOpId("doc"),
                actorId = actorId,
                lamport = lamportClock + 1,
                documentId = documentId,
            ),
        )

    suspend fun applyLocalScene(newJson: String, actorId: String = "local_user"): List<CanvasOp> {
        val doc = currentDoc()
        if (doc.acl != null && !doc.acl.canWrite(actorId)) {
            throw UnauthorizedCanvasMutationException(actorId, canvasId)
        }
        val current = sceneJsonOrEmpty()
        if (newJson == current) return emptyList()

        val generatedOps = CanvasOpDiffer.diff(
            oldSceneJson = current,
            newSceneJson = newJson,
            actorId = actorId,
            lamportSupplier = { ++lamportClock },
        )

        if (generatedOps.isEmpty()) {
            return emptyList()
        }

        applyOps(generatedOps, isRemote = false)
        return generatedOps
    }

    /**
     * Starts listening for remote ops on [syncTransport] and projecting them.
     */
    fun startSync(scope: CoroutineScope): Job? {
        val transport = syncTransport ?: return null
        return scope.launch {
            transport.subscribe(canvasId).collect { remoteOp ->
                applyRemote(remoteOp)
            }
        }
    }

    /**
     * Updates document title.
     */
    suspend fun updateTitle(newTitle: String): CanvasDocument = mutex.withLock {
        val current = currentDoc()
        commitUpdate(
            current.copy(
                title = newTitle,
                updatedAtEpochMs = clock(),
            )
        )
    }

    /**
     * Restores canvas to a prior [CanvasCheckpoint], generating and applying a [CanvasOp.ReplaceSceneOp]
     * locally and broadcasting to peers.
     */
    suspend fun restoreCheckpoint(checkpointId: String, actorId: String = "local_user"): CanvasDocument = mutex.withLock {
        val checkpoint = _checkpoints.value.firstOrNull { it.checkpointId == checkpointId }
            ?: throw IllegalArgumentException("Checkpoint not found: $checkpointId")

        val current = currentDoc()
        if (current.acl != null && !current.acl.canWrite(actorId)) {
            throw UnauthorizedCanvasMutationException(actorId, canvasId)
        }

        val op = CanvasOp.ReplaceSceneOp(
            opId = "restore-${++lamportClock}-${clock()}",
            actorId = actorId,
            lamport = ++lamportClock,
            sceneJson = checkpoint.sceneJson,
        )
        opLog.append(canvasId, op)
        val updated = commitUpdate(
            current.copy(
                revision = current.revision + 1L,
                sceneJson = checkpoint.sceneJson,
                updatedAtEpochMs = clock(),
            ),
            metadata = CanvasCommitMetadata(actorId = actorId, description = "Restored to rev ${checkpoint.revision}"),
        )
        syncTransport?.publish(canvasId, op)
        updated
    }

    companion object {
        const val MAX_CHECKPOINTS: Int = 30
        /**
         * Creates a new [CanvasDocument] in [store] and returns an initialized [CanvasSession].
         */
        suspend fun create(
            store: CanvasDocumentStore,
            options: CanvasCreateOptions = CanvasCreateOptions(),
        ): CanvasSession {
            val effectiveAcl = options.acl ?: if (options.agentId != null) {
                CanvasAcl(
                    ownerUserId = "local_user",
                    writerAgentIds = setOf(options.agentId),
                )
            } else null
            val doc = CanvasDocument(
                id = options.canvasId,
                agentId = options.agentId,
                conversationId = options.conversationId,
                title = options.title,
                revision = 1L,
                sceneJson = options.initialSceneJson,
                acl = effectiveAcl,
                updatedAtEpochMs = options.clock(),
            )
            store.upsert(doc)
            val session = CanvasSession(
                canvasId = options.canvasId,
                store = store,
                opLog = options.opLog,
                syncTransport = options.syncTransport,
                clock = options.clock,
            )
            session._document.value = doc
            return session
        }

        /**
         * Opens a session over the stored canvas [canvasId], or returns null when no such canvas exists.
         */
        suspend fun open(
            store: CanvasDocumentStore,
            canvasId: CanvasId,
            options: CanvasConversationOptions = CanvasConversationOptions(),
        ): CanvasSession? {
            val existing = store.get(canvasId) ?: return null
            return forExisting(store, existing, options)
        }

        private fun forExisting(
            store: CanvasDocumentStore,
            existing: CanvasDocument,
            options: CanvasConversationOptions,
        ): CanvasSession {
            val session = CanvasSession(
                canvasId = existing.id,
                store = store,
                opLog = options.opLog,
                syncTransport = options.syncTransport,
                clock = options.clock,
            )
            session._document.value = existing
            return session
        }

        /**
         * Resolves an existing session for [conversationId], or creates a new one if none exists.
         */
        suspend fun getOrCreateForConversation(
            store: CanvasDocumentStore,
            conversationId: String,
            options: CanvasConversationOptions = CanvasConversationOptions(),
        ): CanvasSession {
            val existing = store.getForConversation(conversationId)
            return if (existing != null) {
                forExisting(store, existing, options)
            } else {
                create(
                    store = store,
                    options = CanvasCreateOptions(
                        title = options.title,
                        conversationId = conversationId,
                        agentId = options.agentId,
                        opLog = options.opLog,
                        syncTransport = options.syncTransport,
                        clock = options.clock,
                    ),
                )
            }
        }
    }
}

/**
 * Metadata for tracking checkpoints and commits in [CanvasSession].
 */
data class CanvasCommitMetadata(
    val actorId: String = "system",
    val description: String = "",
)

/**
 * Options for creating a new [CanvasDocument] and [CanvasSession].
 */
data class CanvasCreateOptions(
    val title: String = "Untitled Canvas",
    val conversationId: String? = null,
    val agentId: String? = null,
    val acl: CanvasAcl? = null,
    val canvasId: CanvasId = CanvasId("canvas-${kotlin.time.Clock.System.now().toEpochMilliseconds()}-${(1000..9999).random()}"),
    val initialSceneJson: String = "",
    val opLog: CanvasOpLog = InMemoryCanvasOpLog(),
    val syncTransport: CanvasSyncTransport? = null,
    val clock: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
)

/**
 * Options for resolving or creating a conversation-linked [CanvasSession].
 */
data class CanvasConversationOptions(
    val agentId: String? = null,
    val title: String = "Conversation Canvas",
    val opLog: CanvasOpLog = InMemoryCanvasOpLog(),
    val syncTransport: CanvasSyncTransport? = null,
    val clock: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
)
