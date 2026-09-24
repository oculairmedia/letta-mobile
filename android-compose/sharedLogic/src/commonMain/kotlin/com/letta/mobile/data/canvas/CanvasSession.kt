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
        if (loaded != null) {
            adoptLamportOf(loaded)
            if (_checkpoints.value.isEmpty()) recordInitialCheckpoint(loaded)
        }
        loaded
    }

    /**
     * Raises the clock above every writer already recorded in [doc]'s scene.
     *
     * A board that is opened again is a board somebody has already written to. This session's
     * writes are settled last-writer-wins against the provenance in that scene, so a clock left
     * at zero makes its first edits OLDER than what they are editing: the projector keeps the
     * existing value and the edit is dropped, with nothing anywhere to report it. Erasing a
     * shape on a re-opened board did exactly that - the removal was discarded and the shape came
     * back the moment the board re-read the scene.
     */
    private fun adoptLamportOf(doc: CanvasDocument) {
        val highest = CanvasOpProjector.maxLamport(doc.sceneJson)
        if (highest > lamportClock) lamportClock = highest
    }

    private fun recordInitialCheckpoint(doc: CanvasDocument) {
        recordCheckpoint(
            doc,
            metadata = CanvasCommitMetadata(
                actorId = doc.agentId ?: "initial",
                description = "Initial state",
            ),
        )
    }

    /**
     * Seeds the session with [doc] and records it as the first checkpoint, so the scene that
     * existed before any caller's first mutation is always restorable. Done before the session
     * is handed out: a sync collector or a local edit can otherwise land first and [load] would
     * then find a non-empty history and skip the original state.
     */
    private fun initialize(doc: CanvasDocument) {
        _document.value = doc
        adoptLamportOf(doc)
        recordInitialCheckpoint(doc)
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
    suspend fun applyLocal(op: CanvasOp): CanvasDocument = mutex.withLock { applyLocalLocked(op) }

    /** [applyLocal]'s body, for callers that must test the scene and act on it under one lock. */
    private suspend fun applyLocalLocked(op: CanvasOp): CanvasDocument {
        val current = currentDoc()
        if (current.acl != null && !current.acl.canWrite(op.actorId)) {
            throw UnauthorizedCanvasMutationException(op.actorId, canvasId)
        }
        opLog.append(canvasId, op)
        if (op.lamport > lamportClock) lamportClock = op.lamport
        val newScene = CanvasOpProjector.project(current.sceneJson, listOf(op))
        val updated = commitScene(newScene)
        syncTransport?.publish(canvasId, op)
        return updated
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

    /**
     * Writes a block document (Cascade JSON) as a local op, placing it at [frame] and colouring it
     * [color] when given; a no-op when nothing would change.
     */
    suspend fun setDocument(
        documentId: String,
        documentJson: String,
        actorId: String = LOCAL_USER_ACTOR_ID,
        frame: CanvasDocumentFrame? = null,
        color: String? = null,
        style: CanvasTextStyle? = null,
        title: String? = null,
    ): CanvasDocument? {
        val existing = documents().firstOrNull { it.id == documentId }
        val unchanged = existing?.json == documentJson &&
            (frame == null || frame == existing.frame) &&
            (color == null || color == existing.color) &&
            (style == null || style == existing.style) &&
            (title == null || title.ifBlank { null } == existing.title)
        if (unchanged) return null
        return applyLocal(
            CanvasOp.SetDocumentOp(
                opId = CanvasOpDiffer.generateOpId("doc"),
                actorId = actorId,
                lamport = lamportClock + 1,
                documentId = documentId,
                documentJson = documentJson,
                frame = frame,
                color = color,
                style = style,
                title = title,
            ),
        )
    }

    /** Renames a block document; an empty [title] clears it. A no-op for a document that is not there. */
    suspend fun retitleDocument(
        documentId: String,
        title: String,
        actorId: String = LOCAL_USER_ACTOR_ID,
    ): CanvasDocument? {
        val existing = documents().firstOrNull { it.id == documentId } ?: return null
        return setDocument(documentId, existing.json, actorId, title = title)
    }

    /** Changes how a block document's text is set; a no-op for a document that is not there. */
    suspend fun restyleDocument(
        documentId: String,
        style: CanvasTextStyle,
        actorId: String = LOCAL_USER_ACTOR_ID,
    ): CanvasDocument? {
        val existing = documents().firstOrNull { it.id == documentId } ?: return null
        return setDocument(documentId, existing.json, actorId, style = style)
    }

    /** Recolours a block document on the board; a no-op for a document that is not there. */
    suspend fun recolorDocument(
        documentId: String,
        colorHex: String,
        actorId: String = LOCAL_USER_ACTOR_ID,
    ): CanvasDocument? {
        val existing = documents().firstOrNull { it.id == documentId } ?: return null
        return setDocument(documentId, existing.json, actorId, color = colorHex)
    }

    /** Moves or resizes a block document on the board; a no-op for a document that is not there. */
    suspend fun moveDocument(
        documentId: String,
        frame: CanvasDocumentFrame,
        actorId: String = LOCAL_USER_ACTOR_ID,
    ): CanvasDocument? {
        val existing = documents().firstOrNull { it.id == documentId } ?: return null
        return setDocument(documentId, existing.json, actorId, frame)
    }

    /** Connector ends bound to block documents, by connector element id. */
    fun arrowBindings(): Map<String, CanvasArrowBinding> = CanvasOpProjector.arrowBindingsOf(sceneJsonOrEmpty())

    /** Binds a connector's ends to documents (both null unbinds); a no-op when already so. */
    /**
     * Applies [ops] as local changes, stamped with this session's clock as they go in.
     *
     * For undo and redo: an inverse is computed when the change happens and applied whenever the
     * person presses the button, by which time the scene has moved on. Applied with the clock it
     * was born with, last-writer-wins simply discards it - the board does not move, and undo
     * looks broken rather than refused.
     */
    suspend fun applyLocalStamped(ops: List<CanvasOp>): CanvasDocument? = mutex.withLock {
        var last: CanvasDocument? = null
        ops.forEach { op ->
            val stamped = op.withStamp(CanvasOpDiffer.generateOpId("undo"), ++lamportClock)
            last = applyLocalLocked(stamped)
        }
        last
    }

    /** Which shape owns which label document; see [CanvasOp.SetLabelOwnerOp]. */
    fun labelOwners(): Map<String, String> = CanvasOpProjector.labelOwnersOf(sceneJsonOrEmpty())

    /**
     * Records that [documentId] is [shapeId]'s label, or releases it when [shapeId] is null.
     *
     * Ownership is what makes the reconciler willing to move or delete a document, so it is
     * written by whoever creates the label and never inferred from the document's name.
     */
    suspend fun setLabelOwner(
        documentId: String,
        shapeId: String?,
        actorId: String = LOCAL_USER_ACTOR_ID,
    ): CanvasDocument = applyLocal(
        CanvasOp.SetLabelOwnerOp(
            opId = CanvasOpDiffer.generateOpId("label"),
            actorId = actorId,
            lamport = lamportClock + 1,
            documentId = documentId,
            shapeId = shapeId,
        ),
    )

    suspend fun bindArrow(
        elementId: String,
        binding: CanvasArrowBinding,
        actorId: String = LOCAL_USER_ACTOR_ID,
    ): CanvasDocument? {
        val current = arrowBindings()[elementId] ?: CanvasArrowBinding()
        if (current == binding) return null
        return applyLocal(
            CanvasOp.SetArrowBindingOp(
                opId = CanvasOpDiffer.generateOpId("bind"),
                actorId = actorId,
                lamport = lamportClock + 1,
                elementId = elementId,
                binding = binding,
            ),
        )
    }

    /** Asset [ref]'s bytes from wherever this canvas is shared (see [CanvasSyncTransport.fetchAsset]). */
    suspend fun fetchAsset(ref: String): ByteArray? = syncTransport?.fetchAsset(canvasId, ref)

    /** The board's background pattern as of the current scene; null when none was set. */
    fun backgroundPattern(): CanvasBackgroundPattern? = CanvasOpProjector.backgroundPatternOf(sceneJsonOrEmpty())

    /** Sets the board's background pattern as a local op; a no-op when it is already that. */
    suspend fun setBackgroundPattern(
        pattern: CanvasBackgroundPattern,
        actorId: String = LOCAL_USER_ACTOR_ID,
    ): CanvasDocument? {
        if (backgroundPattern() == pattern) return null
        return applyLocal(
            CanvasOp.SetBackgroundPatternOp(
                opId = CanvasOpDiffer.generateOpId("bg"),
                actorId = actorId,
                lamport = lamportClock + 1,
                pattern = pattern,
            ),
        )
    }

    /**
     * Moves several block documents at once, as one batch with one set_document op per document,
     * so a group drag lands as a single revision and peers see the notes move together. Documents
     * that are not there, or already at their frame, are skipped; nothing to do returns null.
     */
    suspend fun moveDocuments(
        frames: Map<String, CanvasDocumentFrame>,
        actorId: String = LOCAL_USER_ACTOR_ID,
    ): CanvasDocument? {
        val existing = documents().associateBy { it.id }
        val ops = frames.mapNotNull { (id, frame) ->
            val doc = existing[id] ?: return@mapNotNull null
            if (doc.frame == frame) return@mapNotNull null
            CanvasOp.SetDocumentOp(
                opId = CanvasOpDiffer.generateOpId("doc"),
                actorId = actorId,
                lamport = lamportClock + 1,
                documentId = id,
                documentJson = doc.json,
                frame = frame,
            )
        }
        if (ops.isEmpty()) return null
        return applyLocal(
            CanvasOp.BatchOp(
                opId = CanvasOpDiffer.generateOpId("batch"),
                actorId = actorId,
                lamport = lamportClock + 1,
                ops = ops,
            ),
        )
    }

    /**
     * Removes a block document; null when it was already gone.
     *
     * The test and the op share one lock because the callers repeat themselves: the eraser drags
     * across a note and queues a removal per frame, all of them while the note is still in the
     * projected scene. Without the guard each one appends an op, commits a revision and publishes
     * to peers, for a note that is deleted once.
     */
    suspend fun removeDocument(documentId: String, actorId: String = LOCAL_USER_ACTOR_ID): CanvasDocument? =
        mutex.withLock {
            if (documents().none { it.id == documentId }) return@withLock null
            applyLocalLocked(
                CanvasOp.RemoveDocumentOp(
                    opId = CanvasOpDiffer.generateOpId("doc"),
                    actorId = actorId,
                    lamport = lamportClock + 1,
                    documentId = documentId,
                ),
            )
        }

    suspend fun applyLocalScene(newJson: String, actorId: String = LOCAL_USER_ACTOR_ID): List<CanvasOp> {
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
            transport.deliverTo(canvasId) { remoteOp -> applyRemote(remoteOp) }
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
    suspend fun restoreCheckpoint(checkpointId: String, actorId: String = LOCAL_USER_ACTOR_ID): CanvasDocument = mutex.withLock {
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

        /** The actor id the local human edits as; also the default owner of every new canvas. */
        const val LOCAL_USER_ACTOR_ID: String = "local_user"
        /**
         * Creates a new [CanvasDocument] in [store] and returns an initialized [CanvasSession].
         */
        suspend fun create(
            store: CanvasDocumentStore,
            options: CanvasCreateOptions = CanvasCreateOptions(),
        ): CanvasSession {
            val doc = newDocument(options)
            store.upsert(doc)
            val session = CanvasSession(
                canvasId = options.canvasId,
                store = store,
                opLog = options.opLog,
                syncTransport = options.syncTransport,
                clock = options.clock,
            )
            session.initialize(doc)
            return session
        }

        private fun newDocument(options: CanvasCreateOptions): CanvasDocument = CanvasDocument(
            id = options.canvasId,
            agentId = options.agentId,
            conversationId = options.conversationId,
            title = options.title,
            revision = 1L,
            sceneJson = options.initialSceneJson,
            // A canvas is never created without an ACL: a null ACL reads as "unrestricted" to
            // every mutation path, so an absent agent would otherwise leave the document open
            // to any actor. The local user owns it; the creating agent, when known, may write.
            acl = options.acl ?: CanvasAcl(
                ownerUserId = LOCAL_USER_ACTOR_ID,
                writerAgentIds = options.agentId?.let { setOf(it) }.orEmpty(),
            ),
            updatedAtEpochMs = options.clock(),
        )

        /**
         * Opens a session over the stored canvas [canvasId], or returns null when no such canvas exists.
         */
        suspend fun open(
            store: CanvasDocumentStore,
            canvasId: CanvasId,
            options: CanvasConversationOptions = CanvasConversationOptions(),
        ): CanvasSession? {
            val existing = store.get(canvasId) ?: return null
            val session = CanvasSession(
                canvasId = existing.id,
                store = store,
                opLog = options.opLog,
                syncTransport = options.syncTransport,
                clock = options.clock,
            )
            session.initialize(existing)
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
            val createOptions = CanvasCreateOptions(
                canvasId = CanvasId.forConversation(conversationId),
                title = options.title,
                conversationId = conversationId,
                agentId = options.agentId,
                opLog = options.opLog,
                syncTransport = options.syncTransport,
                clock = options.clock,
            )
            // Lookup and insert are one store step, so two callers opening the same
            // conversation at once share a canvas instead of each persisting their own.
            val doc = store.createForConversationIfAbsent(newDocument(createOptions))
            val session = CanvasSession(
                canvasId = doc.id,
                store = store,
                opLog = options.opLog,
                syncTransport = options.syncTransport,
                clock = options.clock,
            )
            session.initialize(doc)
            return session
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
    val canvasId: CanvasId = CanvasId.generate(),
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
