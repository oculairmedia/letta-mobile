package com.letta.mobile.data.workspace.directory

import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.DefaultAppServerClient
import com.letta.mobile.data.transport.appserver.ScriptedAppServerTransport
import com.letta.mobile.data.transport.appserver.requestId
import com.letta.mobile.data.workspace.relay.WorkspaceClientRoute
import com.letta.mobile.data.workspace.relay.WorkspaceRelayCall
import com.letta.mobile.data.workspace.relay.WorkspaceRelayClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** letta-mobile-zxk2n.6: `list_in_directory` on the wire, and the reasons the picker shows when it cannot browse. */
class AppServerWorkspaceDirectorySourceTest {
    private fun listResponse(command: JsonObject, body: String) =
        """{"type":"list_in_directory_response","request_id":"${command.requestId}","path":"/work",$body}"""

    @Test
    fun listSendsTheProtocolCommandAndReadsTheFolderNames() = runTest {
        val transport = ScriptedAppServerTransport { command ->
            listOf(listResponse(command, """"folders":["app","docs"],"hasMore":true,"total":900,"success":true"""))
        }
        val client = DefaultAppServerClient(transport, parentScope = backgroundScope)
        val source = AppServerWorkspaceDirectorySource(client = { client }, requestId = { "$it-1" }, pageSize = 2)

        val listing = source.list("/work")

        assertEquals(DirectoryListing("/work", listOf("app", "docs"), hasMore = true), listing)
        val sent = transport.sent.single()
        assertEquals("list_in_directory", (sent["type"] as JsonPrimitive).content)
        assertEquals("/work", (sent["path"] as JsonPrimitive).content)
        assertEquals("2", (sent["limit"] as JsonPrimitive).content)
        assertEquals("list-directory-1", (sent["request_id"] as JsonPrimitive).content)
    }

    @Test
    fun aMissingFolderFailsWithAFriendlyReason() = runTest {
        val transport = ScriptedAppServerTransport { command ->
            listOf(listResponse(command, """"folders":[],"hasMore":false,"success":false,"error":"ENOENT: no such file or directory, scandir '/work'""""))
        }
        val client = DefaultAppServerClient(transport, parentScope = backgroundScope)
        val source = AppServerWorkspaceDirectorySource(client = { client }, requestId = { "$it-1" })

        val error = assertFailsWith<DirectoryBrowseException> { source.list("/work") }
        assertEquals(DirectoryBrowseErrors.NOT_FOUND, error.message)
    }

    @Test
    fun overAnIrohRelayBrowsingIsGatedWithAClearReasonAndNothingIsSent() = runTest {
        var relayed = false
        val relay = WorkspaceRelayCall { _, _ ->
            relayed = true
            AppServerInboundFrame.AdminRpcResponse(requestId = "admin-1", success = true)
        }
        val source = AppServerWorkspaceDirectorySource(client = { WorkspaceRelayClient(relay) }, requestId = { it })

        val error = assertFailsWith<DirectoryBrowseException> { source.list("/work") }
        assertEquals(DirectoryBrowseErrors.BROWSE_UNAVAILABLE, error.message)
        assertTrue(!relayed, "list_in_directory is not an allowlisted relay method")
    }

    @Test
    fun withNoConnectionBrowsingIsGatedWithTheSameReason() = runTest {
        val route = WorkspaceClientRoute()
        val source = AppServerWorkspaceDirectorySource(client = route::client, requestId = { it })

        val error = assertFailsWith<DirectoryBrowseException> { source.list("/work") }
        assertEquals(DirectoryBrowseErrors.BROWSE_UNAVAILABLE, error.message)
    }
}
