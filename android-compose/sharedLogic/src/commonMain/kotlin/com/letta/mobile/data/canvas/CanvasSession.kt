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

    private var lamportClock: Long = 0L

    /**
     * Loads the document from [store] into memory and updates [document] state.
     */
    suspend fun load(): CanvasDocument? = mutex.withLock {
        val loaded = store.get(canvasId)
        _document.value = loaded
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
        val current = _document.value ?: store.get(canvasId) ?: CanvasDocument(
            id = canvasId,
            title = "Untitled Canvas",
            revision = 0L,
            sceneJson = "",
            updatedAtEpochMs = clock(),
        )

        val updated = current.copy(
            revision = current.revision + 1L,
            sceneJson = sceneJson,
            updatedAtEpochMs = clock(),
        )

        store.upsert(updated)
        _document.value = updated
        updated
    }

    /**
     * Applies an agent-driven scene replacement, incrementing revision and updating timestamp.
     */
    suspend fun applyAgentReplace(sceneJson: String): CanvasDocument = mutex.withLock {
        val current = _document.value ?: store.get(canvasId) ?: CanvasDocument(
            id = canvasId,
            title = "Untitled Canvas",
            revision = 0L,
            sceneJson = "",
            updatedAtEpochMs = clock(),
        )

        val updated = current.copy(
            revision = current.revision + 1L,
            sceneJson = sceneJson,
            updatedAtEpochMs = clock(),
        )

        store.upsert(updated)
        _document.value = updated
        updated
    }

    /**
     * Applies a single locally-generated [CanvasOp], appends to [opLog], projects state,
     * updates persistence, and publishes to [syncTransport].
     */
    suspend fun applyLocal(op: CanvasOp): CanvasDocument = mutex.withLock {
        opLog.append(canvasId, op)
        if (op.lamport > lamportClock) lamportClock = op.lamport
        val current = _document.value ?: store.get(canvasId) ?: CanvasDocument(
            id = canvasId,
            title = "Untitled Canvas",
            revision = 0L,
            sceneJson = "",
            updatedAtEpochMs = clock(),
        )

        val newScene = CanvasOpProjector.project(current.sceneJson, listOf(op))
        val updated = current.copy(
            revision = current.revision + 1L,
            sceneJson = newScene,
            updatedAtEpochMs = clock(),
        )

        store.upsert(updated)
        _document.value = updated
        syncTransport?.publish(canvasId, op)
        updated
    }

    /**
     * Applies a remote [CanvasOp], ignoring if already present in [opLog] (deduplication),
     * appends to [opLog], projects state, and updates persistence.
     */
    suspend fun applyRemote(op: CanvasOp): CanvasDocument? = mutex.withLock {
        if (opLog.has(canvasId, op.opId)) return null
        opLog.append(canvasId, op)
        if (op.lamport > lamportClock) lamportClock = op.lamport
        val current = _document.value ?: store.get(canvasId) ?: CanvasDocument(
            id = canvasId,
            title = "Untitled Canvas",
            revision = 0L,
            sceneJson = "",
            updatedAtEpochMs = clock(),
        )

        val newScene = CanvasOpProjector.project(current.sceneJson, listOf(op))
        val updated = current.copy(
            revision = current.revision + 1L,
            sceneJson = newScene,
            updatedAtEpochMs = clock(),
        )

        store.upsert(updated)
        _document.value = updated
        updated
    }

    /**
     * Applies a sequence of operations as a single revision bump.
     */
    suspend fun applyOps(ops: List<CanvasOp>, isRemote: Boolean = false): CanvasDocument = mutex.withLock {
        val current = _document.value ?: store.get(canvasId) ?: CanvasDocument(
            id = canvasId,
            title = "Untitled Canvas",
            revision = 0L,
            sceneJson = "",
            updatedAtEpochMs = clock(),
        )

        val opsToApply = mutableListOf<CanvasOp>()
        for (op in ops) {
            if (isRemote && opLog.has(canvasId, op.opId)) continue
            opLog.append(canvasId, op)
            if (op.lamport > lamportClock) lamportClock = op.lamport
            opsToApply.add(op)
        }

        if (opsToApply.isEmpty()) return current

        val newScene = CanvasOpProjector.project(current.sceneJson, opsToApply)
        val updated = current.copy(
            revision = current.revision + 1L,
            sceneJson = newScene,
            updatedAtEpochMs = clock(),
        )

        store.upsert(updated)
        _document.value = updated
        if (!isRemote && syncTransport != null) {
            for (op in opsToApply) {
                syncTransport.publish(canvasId, op)
            }
        }
        updated
    }

    /**
     * Diffs [newJson] against current scene and applies the resulting operations locally.
     */
    suspend fun applyLocalScene(newJson: String, actorId: String = "local_user"): List<CanvasOp> {
        val current = sceneJsonOrEmpty()
        if (newJson == current) return emptyList()

        val generatedOps = CanvasOpDiffer.diff(
            oldSceneJson = current,
            newSceneJson = newJson,
            actorId = actorId,
            lamportSupplier = { ++lamportClock },
        )

        if (generatedOps.isEmpty()) {
            val replaceOp = CanvasOp.ReplaceSceneOp(
                opId = CanvasOpDiffer.generateOpId("replace"),
                actorId = actorId,
                lamport = ++lamportClock,
                sceneJson = newJson,
            )
            applyLocal(replaceOp)
            return listOf(replaceOp)
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
        val current = _document.value ?: store.get(canvasId) ?: CanvasDocument(
            id = canvasId,
            title = newTitle,
            revision = 0L,
            sceneJson = "",
            updatedAtEpochMs = clock(),
        )

        val updated = current.copy(
            title = newTitle,
            updatedAtEpochMs = clock(),
        )

        store.upsert(updated)
        _document.value = updated
        updated
    }

    companion object {
        /**
         * Creates a new [CanvasDocument] in [store] and returns an initialized [CanvasSession].
         */
        suspend fun create(
            store: CanvasDocumentStore,
            title: String = "Untitled Canvas",
            conversationId: String? = null,
            agentId: String? = null,
            canvasId: CanvasId = CanvasId("canvas-${kotlin.time.Clock.System.now().toEpochMilliseconds()}-${(1000..9999).random()}"),
            initialSceneJson: String = "",
            opLog: CanvasOpLog = InMemoryCanvasOpLog(),
            syncTransport: CanvasSyncTransport? = null,
            clock: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
        ): CanvasSession {
            val doc = CanvasDocument(
                id = canvasId,
                agentId = agentId,
                conversationId = conversationId,
                title = title,
                revision = 1L,
                sceneJson = initialSceneJson,
                updatedAtEpochMs = clock(),
            )
            store.upsert(doc)
            val session = CanvasSession(
                canvasId = canvasId,
                store = store,
                opLog = opLog,
                syncTransport = syncTransport,
                clock = clock,
            )
            session._document.value = doc
            return session
        }

        /**
         * Resolves an existing session for [conversationId], or creates a new one if none exists.
         */
        suspend fun getOrCreateForConversation(
            store: CanvasDocumentStore,
            conversationId: String,
            agentId: String? = null,
            title: String = "Conversation Canvas",
            opLog: CanvasOpLog = InMemoryCanvasOpLog(),
            syncTransport: CanvasSyncTransport? = null,
            clock: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
        ): CanvasSession {
            val existing = store.getForConversation(conversationId)
            return if (existing != null) {
                val session = CanvasSession(
                    canvasId = existing.id,
                    store = store,
                    opLog = opLog,
                    syncTransport = syncTransport,
                    clock = clock,
                )
                session._document.value = existing
                session
            } else {
                create(
                    store = store,
                    title = title,
                    conversationId = conversationId,
                    agentId = agentId,
                    opLog = opLog,
                    syncTransport = syncTransport,
                    clock = clock,
                )
            }
        }
    }
}
