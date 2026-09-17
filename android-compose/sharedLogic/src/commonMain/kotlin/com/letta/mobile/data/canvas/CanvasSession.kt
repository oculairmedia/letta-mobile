package com.letta.mobile.data.canvas

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single-player runtime session managing a [CanvasDocument].
 *
 * Owns revision sequencing, in-memory state projection, and persistence synchronization.
 * Pure Kotlin Multiplatform domain logic in :sharedLogic, decoupled from any Compose UI.
 */
class CanvasSession(
    val canvasId: CanvasId,
    private val store: CanvasDocumentStore,
    private val clock: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) {
    private val mutex = Mutex()
    private val _document = MutableStateFlow<CanvasDocument?>(null)
    val document: StateFlow<CanvasDocument?> = _document.asStateFlow()

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
            val session = CanvasSession(canvasId = canvasId, store = store, clock = clock)
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
        ): CanvasSession {
            val existing = store.getForConversation(conversationId)
            return if (existing != null) {
                val session = CanvasSession(canvasId = existing.id, store = store)
                session._document.value = existing
                session
            } else {
                create(
                    store = store,
                    title = title,
                    conversationId = conversationId,
                    agentId = agentId,
                )
            }
        }
    }
}
