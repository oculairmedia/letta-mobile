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
    fun peerCapabilitiesAreIndependent() {
        val read = NotebookPeerGrant("reader")
        val edit = NotebookPeerGrant("editor", mayRead = false, mayEdit = true)
        val reference = NotebookPeerGrant("reference", mayRead = false, mayReference = true)
        assertTrue(mayReadNotebook(read))
        assertFalse(mayEditNotebook(read))
        assertFalse(mayReferenceNotebook(read))
        assertTrue(mayEditNotebook(edit))
        assertFalse(mayReadNotebook(edit))
        assertFalse(mayReferenceNotebook(edit))
        assertTrue(mayReferenceNotebook(reference))
        assertFalse(mayReadNotebook(reference))
        assertFalse(mayEditNotebook(reference))
        assertFalse(mayReadNotebook(null))
    }

    @Test
    fun pluginRequiresMatchingTrustedInstallationAndIndependentGrant() {
        val item = NotebookItem("workflow", "comfyui/workflow", version = 3)
        val contract = NotebookItemContract(item.kind, item.version, executable = true, pluginId = "local-plugin")
        val grant = NotebookPeerGrant("peer", mayEdit = true, mayExecutePlugins = true)
        val installed = NotebookInstalledPlugin("local-plugin", item.kind, item.version, trusted = true)
        assertTrue(mayRunNotebookPlugin(item, contract, grant, installed))
        assertFalse(mayRunNotebookPlugin(item, contract, grant, null))
        assertFalse(mayRunNotebookPlugin(item, contract, grant, installed.copy(trusted = false)))
        assertFalse(mayRunNotebookPlugin(item, contract, grant, installed.copy(pluginId = "other")))
        assertFalse(mayRunNotebookPlugin(item, contract, grant, installed.copy(version = 2)))
        assertFalse(mayRunNotebookPlugin(item, contract.copy(pluginId = null), grant, installed))
        assertFalse(mayRunNotebookPlugin(item, contract, grant.copy(mayExecutePlugins = false), installed))
        assertFalse(mayRunNotebookPlugin(item.copy(kind = "unknown"), contract, grant, installed))
    }

    @Test
    fun agentToolRequiresSeparateAuthorization() {
        val item = NotebookItem("tool", "agent/action", version = 2)
        val contract = NotebookItemContract(item.kind, item.version, agentToolId = "tool-id")
        val grant = NotebookPeerGrant("peer", mayUseAgentTools = true)
        assertTrue(mayUseNotebookAgentTool(item, contract, grant, setOf("tool-id")))
        assertFalse(mayUseNotebookAgentTool(item, contract, grant, emptySet()))
        assertFalse(mayUseNotebookAgentTool(item, contract, grant.copy(mayUseAgentTools = false, mayEdit = true, mayExecutePlugins = true), setOf("tool-id")))
        assertFalse(mayUseNotebookAgentTool(item.copy(version = 3), contract, grant, setOf("tool-id")))
        assertFalse(mayUseNotebookAgentTool(item, null, grant, setOf("tool-id")))
    }

    @Test
    fun independentChatsAndUnknownItemKindsRoundTrip() {
        val document = NotebookDocument(
            NotebookDocumentId("canvas-1"), "Home",
            items = listOf(
                NotebookItem("chat-a", "chat", conversationId = "conv-a"),
                NotebookItem("chat-b", "chat", conversationId = "conv-b"),
                NotebookItem("plugin", "unknown/vendor-kind", version = 3, payloadJson = """{"nodes":[1,2],"opaque":{"x":true}}"""),
            ),
        )
        assertEquals(document, Json.decodeFromString<NotebookDocument>(Json.encodeToString(document)))
    }
}
