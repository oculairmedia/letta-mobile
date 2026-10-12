package com.letta.mobile.data.canvas

import com.letta.mobile.data.document.HistoryBudget
import com.letta.mobile.data.document.InMemoryOpArchive
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The canvas is an instance of the shared-document primitive and writes what its session writes. */
class CanvasSharedDocumentTest {
    private val canvasId = CanvasId("shared-doc-parity")
    private val acl = CanvasAcl(ownerUserId = "local_user")

    private val ops = listOf(
        CanvasOp.AddElementOp("add-a", "local_user", 1, "a", """{"id":"a","text":"original"}"""),
        CanvasOp.AddElementOp("add-b", "local_user", 2, "b", """{"id":"b"}"""),
        CanvasOp.RemoveElementOp("remove-a", "local_user", 3, "a"),
        CanvasOp.SetBackgroundOp("bg", "local_user", 4, "#123456"),
    )

    @Test
    fun aBoardOpenedAsASharedDocumentFoldsOpsExactlyAsItsSession() = runTest {
        val session = CanvasSession.create(
            InMemoryCanvasDocumentStore(),
            CanvasCreateOptions(canvasId = canvasId, acl = acl, clock = { 7L }),
        )
        val created = session.document.value!!
        ops.forEach { session.applyLocal(it) }

        val store = InMemoryCanvasDocumentStore()
        val log = InMemoryCanvasOpLog()
        val doc = openCanvasDocument(created, store, log, CanvasDocumentOptions(clock = { 7L }))
        ops.forEach { doc.apply(it) }

        assertEquals(session.document.value, doc.snapshot.value)
        assertEquals(session.document.value, store.get(canvasId))
        assertEquals(ops.map { it.opId }, log.getOps(canvasId).map { it.opId })
    }

    @Test
    fun theCanvasAclDeniesThroughThePrimitiveAsTheSessionRefuses() = runTest {
        val intruder = CanvasOp.SetBackgroundOp("bg-x", "agent:mallory", 1, "#000000")
        val session = CanvasSession.create(InMemoryCanvasDocumentStore(), CanvasCreateOptions(canvasId = canvasId, acl = acl))
        assertFailsWith<UnauthorizedCanvasMutationException> { session.applyLocal(intruder) }

        val doc = openCanvasDocument(session.document.value!!, InMemoryCanvasDocumentStore(), InMemoryCanvasOpLog())
        assertEquals(listOf(intruder), doc.apply(intruder).denied)
    }

    @Test
    fun aBoardsHistoryIsBoundedByArchiveAndContinue() = runTest {
        val archive = InMemoryOpArchive<CanvasId, CanvasOp, CanvasDocument>()
        val doc = openCanvasDocument(
            CanvasDocument(id = canvasId, title = "Board", acl = acl), InMemoryCanvasDocumentStore(), InMemoryCanvasOpLog(),
            CanvasDocumentOptions(budget = HistoryBudget(maxOps = 2), archive = archive),
        )
        ops.forEach { doc.apply(it) }

        assertEquals(listOf("add-a", "add-b", "remove-a"), archive.archived(canvasId).single().ops.map { it.opId })
        assertEquals(listOf("bg"), doc.history().map { it.opId })
        assertTrue("#123456" in doc.snapshot.value.sceneJson)
    }
}
