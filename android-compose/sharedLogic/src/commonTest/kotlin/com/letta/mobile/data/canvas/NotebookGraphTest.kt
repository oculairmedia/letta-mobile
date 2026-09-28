package com.letta.mobile.data.canvas

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotebookGraphTest {
    @Test
    fun cyclicAndMissingReferencesTerminateAndPreserveOrder() {
        val a = NotebookDocumentId("a")
        val b = NotebookDocumentId("b")
        val c = NotebookDocumentId("c")
        val docs = listOf(
            NotebookDocument(a, "root", items = listOf(
                NotebookItem("to-b", "reference", targetDocumentId = b),
                NotebookItem("to-c", "reference", targetDocumentId = c),
                NotebookItem("missing", "reference", targetDocumentId = NotebookDocumentId("missing")),
            )),
            NotebookDocument(b, "child", items = listOf(NotebookItem("cycle", "reference", targetDocumentId = a))),
            NotebookDocument(c, "child", items = listOf(NotebookItem("shared", "reference", targetDocumentId = b))),
        ).associateBy { it.id }
        assertEquals(listOf(a, b, c), traverseNotebookDocuments(a, docs::get).map { it.id })
    }

    @Test
    fun remotePluginDataCannotExecuteWithoutExplicitContractAndGrant() {
        val executable = NotebookItemContract("comfyui/workflow", 1, executable = true)
        assertFalse(mayExecuteNotebookItem(executable, null))
        assertFalse(mayExecuteNotebookItem(null, NotebookPeerGrant("peer", mayEdit = true, mayExecutePlugins = true)))
        assertFalse(mayExecuteNotebookItem(executable, NotebookPeerGrant("peer", mayEdit = true)))
        assertFalse(mayExecuteNotebookItem(executable, NotebookPeerGrant("peer", mayExecutePlugins = true)))
        assertTrue(mayExecuteNotebookItem(executable, NotebookPeerGrant("peer", mayEdit = true, mayExecutePlugins = true)))
    }

    @Test
    fun independentChatsAndUnknownItemKindsRoundTrip() {
        val document = NotebookDocument(
            NotebookDocumentId("canvas-1"), "Home",
            items = listOf(
                NotebookItem("chat-a", "chat", conversationId = "conv-a"),
                NotebookItem("chat-b", "chat", conversationId = "conv-b"),
                NotebookItem("plugin", "comfyui/workflow", version = 3, payloadJson = """{"nodes":[1,2]}"""),
            ),
        )
        assertEquals(document, Json.decodeFromString<NotebookDocument>(Json.encodeToString(document)))
    }
}
