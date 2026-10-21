package com.letta.mobile.data.canvas

import com.letta.mobile.data.model.CanvasActorId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CanvasSessionTest {

    @Test
    fun restoreUsesExactlyOneLamport() = runTest {
        val log = InMemoryCanvasOpLog()
        val session = CanvasSession.create(
            store = InMemoryCanvasDocumentStore(),
            options = CanvasCreateOptions(title = "Clock", initialSceneJson = """{"elements":[]}""", opLog = log),
        )
        val checkpoint = session.checkpoints.value.single()
        val edits = session.applyLocalScene("""{"elements":[{"id":"a","type":"rect"}]}""")
        val before = edits.maxOf { it.lamport }
        session.restoreCheckpoint(checkpoint.checkpointId)
        val restored = log.getOps(session.canvasId, before).single()
        assertEquals(before + 1, restored.lamport)
        assertTrue(restored.opId.startsWith("restore-${restored.lamport}-"))
    }

    @Test
    fun concurrentLocalScenesHaveDistinctLamports() = runTest {
        val blocked = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val backing = InMemoryCanvasOpLog()
        val log = object : CanvasOpLog by backing {
            override suspend fun append(canvasId: CanvasId, op: CanvasOp) {
                if (!blocked.isCompleted) {
                    blocked.complete(Unit)
                    release.await()
                }
                backing.append(canvasId, op)
            }
        }
        val session = CanvasSession.create(
            store = InMemoryCanvasDocumentStore(),
            options = CanvasCreateOptions(title = "Concurrent", initialSceneJson = """{"elements":[]}""", opLog = log),
        )
        val first = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            session.applyLocalScene("""{"elements":[{"id":"first","type":"rect"}]}""")
        }
        blocked.await()
        val second = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            session.applyLocalScene("""{"elements":[{"id":"second","type":"rect"}]}""")
        }
        release.complete(Unit)
        val edits = first.await() + second.await()
        assertEquals(edits.size, edits.map { it.lamport }.toSet().size)
        // Fail on reverting the outer lock: the second diff must see and remove the first scene.
        assertEquals("first", edits.filterIsInstance<CanvasOp.RemoveElementOp>().single().elementId)
    }

    @Test
    fun revisionIncrementsOnSave() = runTest {
        val store = InMemoryCanvasDocumentStore()
        val session = CanvasSession.create(
            store = store,
            options = CanvasCreateOptions(
                title = "Design Draft",
                initialSceneJson = """{"elements":[]}""",
            ),
        )

        assertEquals(1L, session.document.value?.revision)
        assertEquals("""{"elements":[]}""", session.sceneJsonOrEmpty())

        val savedDoc1 = session.saveScene("""{"elements":[{"id":"1"}]}""")
        assertEquals(2L, savedDoc1.revision)
        assertEquals(2L, session.document.value?.revision)
        assertEquals("""{"elements":[{"id":"1"}]}""", session.sceneJsonOrEmpty())

        val savedDoc2 = session.saveScene("""{"elements":[{"id":"1"},{"id":"2"}]}""")
        assertEquals(3L, savedDoc2.revision)
        assertEquals(3L, session.document.value?.revision)
    }

    @Test
    fun emptySceneAllowedOnSave() = runTest {
        val store = InMemoryCanvasDocumentStore()
        val session = CanvasSession.create(
            store = store,
            options = CanvasCreateOptions(
                title = "Empty Scene Test",
                initialSceneJson = """{"elements":[{"id":"shape"}]}""",
            ),
        )

        assertEquals(1L, session.document.value?.revision)

        val clearedDoc = session.saveScene("")
        assertEquals(2L, clearedDoc.revision)
        assertEquals("", clearedDoc.sceneJson)
        assertEquals("", session.sceneJsonOrEmpty())

        // Verify persisted in store
        val inStore = store.get(session.canvasId)
        assertEquals("", inStore?.sceneJson)
        assertEquals(2L, inStore?.revision)
    }

    @Test
    fun killAndReopenSameCanvasIdRestoresElements() = runTest {
        val store = InMemoryCanvasDocumentStore()
        val canvasId = CanvasId("canvas-persistent-1")

        // Session 1: Create and write diagram elements
        val session1 = CanvasSession.create(
            store = store,
            options = CanvasCreateOptions(
                canvasId = canvasId,
                title = "Architecture",
                initialSceneJson = """{"bgColor":-1,"elements":[{"id":"box1","type":"rect"}]}""",
            ),
        )
        session1.saveScene("""{"bgColor":-1,"elements":[{"id":"box1"},{"id":"box2"}]}""")

        // Simulate kill / reopen by creating a completely fresh CanvasSession pointing to same canvasId
        val session2 = CanvasSession(canvasId = canvasId, store = store)
        val loaded = session2.load()

        assertNotNull(loaded)
        assertEquals(canvasId, loaded.id)
        assertEquals(2L, loaded.revision)
        assertEquals("""{"bgColor":-1,"elements":[{"id":"box1"},{"id":"box2"}]}""", loaded.sceneJson)
        assertEquals("""{"bgColor":-1,"elements":[{"id":"box1"},{"id":"box2"}]}""", session2.sceneJsonOrEmpty())
        assertEquals("Architecture", loaded.title)
    }

    @Test
    fun aCanvasCreatedWithoutAnAgentIsOwnerOnlyNotUnrestricted() = runTest {
        val store = InMemoryCanvasDocumentStore()
        val session = CanvasSession.create(store = store, options = CanvasCreateOptions(title = "Solo"))

        val acl = session.document.value?.acl
        assertNotNull(acl, "a new canvas must always carry an ACL")
        assertEquals(CanvasSession.LOCAL_USER_ACTOR_ID, acl.ownerUserId)
        assertEquals(emptySet(), acl.writerAgentIds)
        assertTrue(acl.canWrite(CanvasSession.LOCAL_USER_ACTOR_ID))
        assertFalse(acl.canWrite("some-agent"))
        assertFailsWith<UnauthorizedCanvasMutationException> {
            session.applyAgentReplace("{}", actorId = CanvasActorId("some-agent"))
        }

        val withAgent = CanvasSession.create(store = store, options = CanvasCreateOptions(agentId = "agent-7"))
        assertTrue(withAgent.document.value?.acl?.canWrite("agent-7") == true)
    }

    @Test
    fun theInitialSceneIsACheckpointBeforeAnyoneCanMutateTheSession() = runTest {
        val store = InMemoryCanvasDocumentStore()
        val created = CanvasSession.create(
            store = store,
            options = CanvasCreateOptions(canvasId = CanvasId("cp-first"), initialSceneJson = """{"elements":[]}"""),
        )
        assertEquals(1, created.checkpoints.value.size)
        assertEquals("Initial state", created.checkpoints.value.single().description)

        // A mutation before load() used to leave the original scene unrestorable.
        created.saveScene("""{"elements":[{"id":"1"}]}""")
        created.load()
        val oldest = created.checkpoints.value.last()
        assertEquals(1L, oldest.revision)
        assertEquals("""{"elements":[]}""", oldest.sceneJson)

        val reopened = CanvasSession.getOrCreateForConversation(
            store = store,
            conversationId = ConversationId("conv-cp"),
            options = CanvasConversationOptions(),
        )
        reopened.saveScene("""{"elements":[{"id":"x"}]}""")
        val again = CanvasSession.getOrCreateForConversation(store = store, conversationId = ConversationId("conv-cp"))
        assertEquals(1, again.checkpoints.value.size)
        assertEquals(2L, again.checkpoints.value.single().revision)
    }

    @Test
    fun getOrCreateForConversationReusesExistingDocument() = runTest {
        val store = InMemoryCanvasDocumentStore()
        val convId = "conversation-alpha"

        val sessionA = CanvasSession.getOrCreateForConversation(
            store = store,
            conversationId = ConversationId(convId),
            options = CanvasConversationOptions(
                agentId = "agent-x",
                title = "Conv Canvas",
            ),
        )
        val initialId = sessionA.canvasId
        sessionA.saveScene("""{"elements":[{"id":"note"}]}""")

        // Call getOrCreate again for same conversation
        val sessionB = CanvasSession.getOrCreateForConversation(
            store = store,
            conversationId = ConversationId(convId),
        )

        assertEquals(initialId, sessionB.canvasId)
        assertEquals("""{"elements":[{"id":"note"}]}""", sessionB.sceneJsonOrEmpty())
        assertEquals(2L, sessionB.document.value?.revision)
    }
}
