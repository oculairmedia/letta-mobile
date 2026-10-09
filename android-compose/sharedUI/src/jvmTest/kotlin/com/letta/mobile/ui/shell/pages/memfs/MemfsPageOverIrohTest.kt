@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.pages.memfs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.memory.memfs.AppServerMemfsSource
import com.letta.mobile.data.memory.memfs.MemfsPageController
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.workspace.relay.WorkspaceClientRoute
import com.letta.mobile.data.workspace.relay.WorkspaceRelayCall
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.Json

/**
 * letta-mobile-bzvro.37: the MemFS page over an Iroh host that relays workspace commands lists the
 * agent's files; over a connection that cannot relay it still explains that it needs a direct one.
 */
class MemfsPageOverIrohTest {
    private val relayingHost = WorkspaceRelayCall { _, _ ->
        AppServerInboundFrame.AdminRpcResponse(
            requestId = "admin-1",
            success = true,
            result = Json.parseToJsonElement(
                """{"frames":[{"type":"list_memory_response","request_id":"h","entries":[""" +
                    """{"relative_path":"system/human/communication_style.md","is_system":true,"content":"x","size":1}],"done":true,"success":true}]}""",
            ),
        )
    }

    @Test
    fun overARelayingIrohHostTheFilesShowInsteadOfTheDirectConnectionMessage() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        show(WorkspaceClientRoute(relay = { relayingHost }), scope)

        waitUntil(timeoutMillis = TIMEOUT_MS) { onAllNodesWithTag(MemfsPageTags.file(FILE)).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText(WorkspaceClientRoute.NO_CONNECTION).assertDoesNotExist()
        scope.cancel()
    }

    @Test
    fun overAConnectionThatCannotRelayTheMessageStillShows() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        show(WorkspaceClientRoute(), scope)

        waitUntil(timeoutMillis = TIMEOUT_MS) { onAllNodesWithText(WorkspaceClientRoute.NO_CONNECTION).fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithTag(MemfsPageTags.file(FILE)).fetchSemanticsNodes().let { check(it.isEmpty()) }
        scope.cancel()
    }

    private fun ComposeUiTest.show(route: WorkspaceClientRoute, scope: CoroutineScope) {
        val controller = MemfsPageController(AppServerMemfsSource(client = route::client, events = emptyFlow(), requestId = { it }), scope)
        controller.selectAgent("agent-1")
        setContent {
            MaterialTheme {
                val state by controller.state.collectAsState()
                Box(Modifier.width(1200.dp).height(800.dp)) { MemfsPage(state = state, actions = controller) }
            }
        }
    }

    private companion object {
        const val FILE = "system/human/communication_style.md"
        const val TIMEOUT_MS = 5_000L
    }
}
