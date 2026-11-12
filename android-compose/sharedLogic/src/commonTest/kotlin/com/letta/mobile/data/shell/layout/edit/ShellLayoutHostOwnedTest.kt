package com.letta.mobile.data.shell.layout.edit

import com.letta.mobile.data.controller.extras.ExternalToolCaller
import com.letta.mobile.data.controller.extras.ExternalToolResult
import com.letta.mobile.data.shell.layout.ModuleSlot
import com.letta.mobile.data.shell.layout.ShellLanes
import com.letta.mobile.data.shell.layout.ShellLayoutConfig
import com.letta.mobile.data.shell.layout.ShellLayoutConfigStore
import com.letta.mobile.data.shell.layout.ShellViews
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The secret request card is mounted by the shell itself (letta-mobile-n2stx.2.4): no edit, and so
 * no agent through `shell_layout`, can place, move, remove or reorder around it, in any lane, and a
 * layout that names it anyway (a hand-edited config) never draws it in a lane.
 */
class ShellLayoutHostOwnedTest {
    private val secret = ModuleSlot(ShellViews.SECRET_REQUEST)
    private val page = ModuleSlot(ShellViews.PAGE)

    private fun rules(vararg ops: ShellLayoutOp): List<ShellLayoutEditRule> =
        assertIs<ShellLayoutEditResult.Rejected>(ShellLayoutEdits.apply(ShellLayoutConfig.Default, ops.toList())).errors.map { it.rule }

    @Test
    fun everyOpNamingTheSecretCardIsRefusedInEveryLane() {
        val lanes = ShellLayoutCatalog.lanes.map { it.lane }
        val refusals = lanes.flatMap { lane ->
            listOf(
                ShellLayoutOp.Add(lane, secret, 0),
                ShellLayoutOp.Add(lane, secret),
                ShellLayoutOp.Remove(lane, secret),
                ShellLayoutOp.Move(lane, secret, ShellLanes.MAIN, 0),
                ShellLayoutOp.SetLane(lane, ShellLayoutEdits.slots(ShellLayoutConfig.Default, lane) + secret),
            ).map { op -> op to rules(op) }
        }
        val wrong = refusals.filterNot { (_, found) -> ShellLayoutEditRule.HOST_OWNED_VIEW in found }
        assertEquals(emptyList(), wrong)
        assertEquals(setOf(ShellViews.SECRET_REQUEST), ShellViews.HostOwned)
    }

    @Test
    fun theCatalogNeverOffersTheSecretCardToAnAgent() {
        val offered = ShellLayoutCatalog.lanes.filter { lane -> lane.view(ShellViews.SECRET_REQUEST) != null }
        assertEquals(emptyList(), offered)
    }

    @Test
    fun aConfigNamingTheSecretCardNeverDrawsItInALane() {
        val tampered = ShellLayoutConfig.Default.copy(
            lanes = ShellLayoutConfig.Default.lanes + (ShellLanes.MAIN to listOf(secret, page)) + (ShellLanes.SIDE to listOf(secret)),
        )
        assertEquals(listOf(page), tampered.mainLane())
        assertEquals(listOf(page), tampered.mainLane(room = 1))
        assertTrue(tampered.sideLane().isEmpty())
        assertFalse(ShellLanes.run { listOf(MAIN, SIDE, HOME, RAIL, PANEL, CHAT) }.any { secret in tampered.lane(it) })
    }

    @Test
    fun theLayoutToolRefusesTheSecretCardWithItsOwnRule() = runTest {
        val port = ShellLayoutAgentPort()
        val store = ShellLayoutConfigStore(InMemorySecureSettingsStore(), "shell.host-owned.lanes")
        port.attach(store)
        val tool = ShellLayoutTool(port)
        val input = """{"action":"apply","ops":[{"op":"add","lane":"main","view":"shell.secret-request","index":0}]}"""
        val result = tool.invoke(Json.parseToJsonElement(input).jsonObject, ExternalToolCaller(agentId = "agent-layout-test"))
        val errors = Json.parseToJsonElement(assertIs<ExternalToolResult.Error>(result).error).jsonObject["errors"]!!.jsonArray
        assertEquals("host_owned_view", errors.first().jsonObject["rule"]!!.jsonPrimitive.content)
        assertEquals(ShellLayoutConfig.Default, store.config.value)
    }
}
