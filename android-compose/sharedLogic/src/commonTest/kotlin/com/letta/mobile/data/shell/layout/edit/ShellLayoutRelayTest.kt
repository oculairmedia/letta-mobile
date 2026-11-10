package com.letta.mobile.data.shell.layout.edit

import com.letta.mobile.data.controller.extras.ExternalToolCaller
import com.letta.mobile.data.controller.extras.ExternalToolResult
import com.letta.mobile.data.controller.extras.relay.AppToolCallId
import com.letta.mobile.data.controller.extras.relay.AppToolCallRequest
import com.letta.mobile.data.controller.extras.relay.AppToolCallResult
import com.letta.mobile.data.controller.extras.relay.AppToolCallScope
import com.letta.mobile.data.controller.extras.relay.AppToolRelayLink
import com.letta.mobile.data.controller.extras.relay.AppToolRelayResponder
import com.letta.mobile.data.home.HomeViews
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.shell.layout.ShellLayoutConfig
import com.letta.mobile.data.shell.layout.ShellLayoutConfigStore
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `shell_layout` relayed from an Iroh host: the host's stand-in is the app tool's definition, and
 * host and app halves joined in process (the frames passing as text between them) change the app's
 * shell exactly as the app's own tool would, with the same receipt and Undo. The Iroh streams
 * between them are not exercised here.
 */
class ShellLayoutRelayTest {
    private val caller = ExternalToolCaller(agentId = "agent-layout", conversationId = "conv-layout", toolCallId = "tc-7")
    private val recentsFirst = """{"action":"apply","ops":[{"op":"move","lane":"home","view":"home.recents","index":0}]}"""

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun attachedPort(key: String): Pair<ShellLayoutAgentPort, ShellLayoutConfigStore> {
        val port = ShellLayoutAgentPort()
        val store = ShellLayoutConfigStore(InMemorySecureSettingsStore(), key)
        port.attach(store)
        return port to store
    }

    @Test
    fun theHostsStandInIsTheAppToolsNameDescriptionAndSchema() {
        val standIn = ShellLayoutRelay.hostTools(ShellLayoutRelay.host()).single()
        val local = ShellLayoutTool(ShellLayoutAgentPort())

        assertEquals(local.name, standIn.name)
        assertEquals(local.description, standIn.description)
        assertEquals(local.inputSchema, standIn.inputSchema)
        assertEquals(setOf(ShellLayoutTool.NAME), ShellLayoutRelay.TOOLS)
    }

    @Test
    fun anAgentsCallOnTheHostChangesTheAppsShellWithTheLocalToolsReceipt() = runTest {
        val (appPort, appStore) = attachedPort("shell.relay.app")
        val (localPort, _) = attachedPort("shell.relay.local")
        val responder = AppToolRelayResponder { ShellLayoutRelay.answerer(appPort) }
        val host = ShellLayoutRelay.host()
        lateinit var link: AppToolRelayLink
        link = host.link(
            send = { frame ->
                backgroundScope.launch { responder.answer(json(frame))?.let { link.receive(json(it)) } }
                true
            },
            ready = { true },
        )
        host.bindViewers { conversation -> if (conversation == "conv-layout") listOf(link) else emptyList() }
        val changes = mutableListOf<ShellLayoutChange>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { appPort.changes.toList(changes) }

        val relayed = assertIs<ExternalToolResult.Success>(ShellLayoutRelay.hostTools(host).single().invoke(json(recentsFirst), caller))
        val local = assertIs<ExternalToolResult.Success>(ShellLayoutTool(localPort).invoke(json(recentsFirst), caller))

        assertEquals(json(local.content), json(relayed.content))
        assertEquals(HomeViews.RECENTS, appStore.config.value.homeLane().first().view)
        val change = changes.single()
        assertEquals(ShellLayoutAuthor.Agent(AgentId("agent-layout")), change.author)
        assertTrue(appPort.undo(change))
        assertEquals(ShellLayoutConfig.Default, appStore.config.value)
    }

    @Test
    fun theAppsRefusalComesBackAsTheHostToolsError() = runTest {
        val noShell = ShellLayoutRelay.answerer(ShellLayoutAgentPort())
        val request = AppToolCallRequest(AppToolCallId("c"), ShellLayoutTool.NAME, json("""{"action":"get"}"""), AppToolCallScope())

        val result = noShell.answer(request)

        assertTrue(result.isError, "no shell attached is a refusal: ${result.text}")
    }

    @Test
    fun theAnswererRefusesEveryToolOffTheAllowList() = runTest {
        val (port, store) = attachedPort("shell.relay.refuse")
        val answerer = ShellLayoutRelay.answerer(port)

        val result = answerer.answer(AppToolCallRequest(AppToolCallId("c"), "device_action", json(recentsFirst), AppToolCallScope()))

        assertEquals(AppToolCallResult("This app does not run device_action for its host.", isError = true), result)
        assertEquals(ShellLayoutConfig.Default, store.config.value)
    }
}
