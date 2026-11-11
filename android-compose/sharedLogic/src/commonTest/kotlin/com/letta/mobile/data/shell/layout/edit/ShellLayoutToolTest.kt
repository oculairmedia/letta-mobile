package com.letta.mobile.data.shell.layout.edit

import com.letta.mobile.data.controller.extras.ExternalToolCaller
import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.extras.ExternalToolResult
import com.letta.mobile.data.home.HomeViews
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.shell.layout.ModuleSlot
import com.letta.mobile.data.shell.layout.ShellLanes
import com.letta.mobile.data.shell.layout.ShellLayoutConfig
import com.letta.mobile.data.shell.layout.ShellLayoutConfigStore
import com.letta.mobile.data.shell.layout.ShellViews
import com.letta.mobile.data.shell.sidemenu.PanelViews
import com.letta.mobile.data.shell.sidemenu.RailViews
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The `shell_layout` tool over a port with a shell attached: each action, dry runs, refusals the
 * model can correct from, and the clear error when no shell is on screen.
 */
class ShellLayoutToolTest {
    private val port = ShellLayoutAgentPort()
    private val storeSettings = InMemorySecureSettingsStore()
    private val store = ShellLayoutConfigStore(storeSettings, "shell.tool.lanes")
    private val tool = ShellLayoutTool(port)
    private val caller = ExternalToolCaller(agentId = "agent-layout-test")

    init {
        port.attach(store)
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private suspend fun call(input: String): ExternalToolResult = tool.invoke(json(input), caller)

    private suspend fun success(input: String): JsonObject = json(assertIs<ExternalToolResult.Success>(call(input)).content)

    private suspend fun error(input: String): JsonObject = json(assertIs<ExternalToolResult.Error>(call(input)).error)

    private val recentsFirst = """{"action":"apply","ops":[{"op":"move","lane":"home","view":"home.recents","index":0}]}"""

    @Test
    fun chatDryRunApplyUndoResetAndProtectedRefusalUseTheRealPort() = runTest {
        val op = """"ops":[{"op":"remove","lane":"chat","view":"chat.status"}]"""
        success("""{"action":"apply","dry_run":true,$op}""")
        assertEquals(ShellLayoutConfig.Default, store.config.value)
        success("""{"action":"apply",$op}""")
        assertEquals(listOf("chat.transcript", "chat.composer"), store.config.value.chatLane().map { it.view.value })
        success("""{"action":"undo"}""")
        assertEquals(ShellLayoutConfig.Default, store.config.value)
        val refused = error("""{"action":"apply","ops":[{"op":"remove","lane":"chat","view":"chat.transcript"}]}""")
        assertEquals("chat_read_send_stays", refused["errors"]!!.jsonArray.first().jsonObject["rule"]!!.jsonPrimitive.content)
        success("""{"action":"apply",$op}""")
        success("""{"action":"reset"}""")
        assertEquals(ShellLayoutConfig.Default, store.config.value)
    }

    @Test
    fun theSchemaIsStrictAndNamesEveryActionOpAndLane() {
        val schema = tool.inputSchema
        assertEquals("shell_layout", tool.name)
        assertEquals(false, schema["additionalProperties"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("action"), schema["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        val properties = schema["properties"]!!.jsonObject
        assertEquals(listOf("get", "apply", "undo", "reset"), properties["action"]!!.enumValues())
        val op = properties["ops"]!!.jsonObject["items"]!!.jsonObject
        assertEquals(false, op["additionalProperties"]!!.jsonPrimitive.boolean)
        val opProperties = op["properties"]!!.jsonObject
        assertEquals(ShellLayoutToolInput.opNames, opProperties["op"]!!.enumValues())
        assertEquals(ShellLayoutCatalog.lanes.map { it.lane }, opProperties["lane"]!!.enumValues())
        assertTrue("immediately" in tool.description && "Undo" in tool.description)
    }

    @Test
    fun noteSlotsAreSyntacticPersistedUndoableAndDryRunsPublishNothing() = runTest {
        val changes = mutableListOf<ShellLayoutChange>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { port.changes.toList(changes) }
        val op = """"ops":[{"op":"add","lane":"side","view":"shell.note","object":"unknown-or-denied"}]"""
        success("""{"action":"apply","dry_run":true,$op}""")
        assertEquals(ShellLayoutConfig.Default, store.config.value)
        assertTrue(changes.isEmpty())
        success("""{"action":"apply",$op}""")
        val note = store.config.value.sideLane().last()
        assertEquals("shell.note", note.view.value)
        assertEquals("unknown-or-denied", note.objectRef)
        val reloaded = ShellLayoutConfigStore(storeSettings, "shell.tool.lanes")
        assertEquals(note, reloaded.config.value.sideLane().last())
        success("""{"action":"undo"}""")
        assertEquals(ShellLayoutConfig.Default, store.config.value)
    }

    @Test
    fun aHostToolRegistryOffersIt() {
        assertTrue(ExternalToolRegistry.hostTools(listOf(tool)).listAdvertisedTools().any { it.name == ShellLayoutTool.NAME })
    }

    @Test
    fun getReturnsTheLanesTheCatalogAndTheRules() = runTest {
        val receipt = success("""{"action":"get"}""")
        val home = receipt["lanes"]!!.jsonObject["home"]!!.jsonArray.map { it.jsonObject["view"]!!.jsonPrimitive.content }
        assertEquals(HomeViews.Default.map { it.value }, home)
        val catalogLanes = receipt["catalog"]!!.jsonArray.map { it.jsonObject["lane"]!!.jsonPrimitive.content }
        assertEquals(ShellLayoutCatalog.lanes.map { it.lane }, catalogLanes)
        val chat = receipt["catalog"]!!.jsonArray.single { it.jsonObject["lane"]!!.jsonPrimitive.content == "chat" }.jsonObject
        val description = chat["description"]!!.jsonPrimitive.content
        assertTrue("Modules other than transcript, status and composer show on wide windows only" in description)
        assertTrue("phones show chat content alone" in description)
        assertEquals(0, receipt["undo_steps"]!!.jsonPrimitive.int)
        val rules = receipt["rules"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("${RailViews.HOME.value} stays in lane ${ShellLanes.RAIL}." in rules)
    }

    @Test
    fun applyChangesTheLayoutAtOnceAndPublishesTheChange() = runTest {
        val changes = mutableListOf<ShellLayoutChange>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { port.changes.toList(changes) }

        val receipt = success(recentsFirst)

        assertEquals(HomeViews.RECENTS, store.config.value.homeLane().first().view)
        assertEquals(true, receipt["applied"]!!.jsonPrimitive.boolean)
        assertEquals(true, receipt["visible_to_user"]!!.jsonPrimitive.boolean)
        assertEquals("Home: reordered", receipt["summary"]!!.jsonPrimitive.content)
        val change = receipt["changes"]!!.jsonArray.single().jsonObject
        assertEquals("home", change["lane"]!!.jsonPrimitive.content)
        assertEquals("home.composer", change["before"]!!.jsonArray.first().jsonObject["view"]!!.jsonPrimitive.content)
        assertEquals("home.recents", change["after"]!!.jsonArray.first().jsonObject["view"]!!.jsonPrimitive.content)

        val published = changes.single()
        assertEquals(ShellLayoutAuthor.Agent(AgentId("agent-layout-test")), published.author)
        assertEquals(ShellLayoutConfig.Default, published.before)
        assertTrue(port.undo(published))
        assertEquals(ShellLayoutConfig.Default, store.config.value)
    }

    @Test
    fun unseenChangeIsClaimedOnceAndOnlyTheLatestCanBeUndone() = runTest {
        success(recentsFirst)
        val first = port.claimUnseenChange()!!
        assertEquals(null, port.claimUnseenChange())
        success("""{"action":"apply","ops":[{"op":"remove","lane":"panel","view":"panel.newChat"}]}""")
        val second = port.claimUnseenChange()!!
        assertEquals(null, port.claimUnseenChange())
        assertEquals(false, port.undo(first))
        assertTrue(port.undo(second))
    }

    @Test
    fun unseenChangeIsReplacedAndDiscardedWhenItsStoreOrLayoutChanges() = runTest {
        val changes = mutableListOf<ShellLayoutChange>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { port.changes.toList(changes) }
        success(recentsFirst)
        success("""{"action":"apply","ops":[{"op":"remove","lane":"panel","view":"panel.newChat"}]}""")
        assertEquals(null, port.claimUnseenChange(changes.first()))
        val latest = port.claimUnseenChange(changes.last())!!
        assertEquals(HomeViews.RECENTS, latest.after.homeLane().first().view)
        assertEquals(false, latest.after.panelLane().any { it.view == PanelViews.NEW_CHAT })
        assertEquals(null, port.claimUnseenChange())
        success("""{"action":"reset"}""")
        store.save(latest.after)
        assertEquals(null, port.claimUnseenChange())
        success("""{"action":"reset"}""")
        val other = ShellLayoutConfigStore(InMemorySecureSettingsStore(), "other")
        port.attach(other)
        port.detach(other)
        assertEquals(null, port.claimUnseenChange())
    }

    @Test
    fun aDryRunChecksWithoutChangingOrNotifying() = runTest {
        val changes = mutableListOf<ShellLayoutChange>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { port.changes.toList(changes) }

        val receipt = success("""{"action":"apply","dry_run":true,"ops":[{"op":"remove","lane":"panel","view":"panel.newChat"}]}""")

        assertEquals(true, receipt["dry_run"]!!.jsonPrimitive.boolean)
        assertEquals(false, receipt["applied"]!!.jsonPrimitive.boolean)
        assertEquals("Side menu: removed New chat", receipt["summary"]!!.jsonPrimitive.content)
        assertEquals(ShellLayoutConfig.Default, store.config.value)
        success("""{"action":"get"}""")
        assertTrue(changes.isEmpty(), "neither get nor a dry run tells the person anything")
    }

    @Test
    fun anInvalidEditIsRefusedWithEveryProblemAndNothingApplied() = runTest {
        val receipt = error(
            """{"action":"apply","ops":[
                {"op":"remove","lane":"panel","view":"panel.newChat"},
                {"op":"remove","lane":"panel","view":"panel.settings"},
                {"op":"add","lane":"side","view":"shell.canvas"}
            ]}""",
        )
        assertEquals(false, receipt["ok"]!!.jsonPrimitive.boolean)
        val errors = receipt["errors"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(1, 2), errors.map { it["op_index"]!!.jsonPrimitive.int })
        assertEquals(listOf("settings_stays", "object_required"), errors.map { it["rule"]!!.jsonPrimitive.content })
        assertTrue("Nothing was applied" in receipt["hint"]!!.jsonPrimitive.content)
        assertEquals(ShellLayoutConfig.Default, store.config.value)
    }

    @Test
    fun theRailKeepsItsHomeButtonButHomeMayMoveWithinIt() = runTest {
        val receipt = error(
            """{"action":"apply","ops":[
                {"op":"remove","lane":"rail","view":"rail.home"},
                {"op":"move","lane":"rail","view":"rail.home","to_lane":"panel"},
                {"op":"set_lane","lane":"rail","slots":[{"view":"rail.agents"},{"view":"rail.new"}]}
            ]}""",
        )
        val errors = receipt["errors"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(0, 1, 2), errors.map { it["op_index"]!!.jsonPrimitive.int })
        assertEquals(List(3) { "home_stays" }, errors.map { it["rule"]!!.jsonPrimitive.content })
        assertEquals("rail.home must stay in lane rail.", errors.first()["detail"]!!.jsonPrimitive.content)
        assertEquals(ShellLayoutConfig.Default, store.config.value)

        success("""{"action":"apply","ops":[{"op":"move","lane":"rail","view":"rail.home","index":2}]}""")
        assertEquals(listOf(RailViews.AGENTS, RailViews.NEW, RailViews.HOME), store.config.value.railLane().map { it.view })
    }

    @Test
    fun malformedInputIsRefusedFieldByField() = runTest {
        assertEquals(listOf("malformed"), rules("""{"action":"get","extra":1}"""))
        assertEquals(listOf("malformed"), rules("""{}"""))
        assertEquals(listOf("malformed"), rules("""{"action":"tidy"}"""))
        assertEquals(listOf("malformed"), rules("""{"action":"undo","ops":[]}"""))
        assertEquals(listOf("malformed"), rules("""{"action":"apply","ops":"all"}"""))
        val wrongFields = """{"action":"apply","ops":[{"op":"add","lane":7,"view":"home.now","colour":"red"}]}"""
        assertEquals(listOf("malformed", "malformed"), rules(wrongFields))
        assertEquals(listOf("malformed"), rules("""{"action":"apply","ops":[{"op":"move","lane":"home","view":"home.now","index":"1"}]}"""))
        assertEquals(listOf("op_count"), rules("""{"action":"apply","ops":[]}"""))
    }

    private fun JsonElement.enumValues(): List<String> = jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }

    private suspend fun rules(text: String): List<String> =
        error(text)["errors"]!!.jsonArray.map { it.jsonObject["rule"]!!.jsonPrimitive.content }

    @Test
    fun anAgentAddsTheTasksModuleAndTheReceiptNamesIt() = runTest {
        val receipt = success("""{"action":"apply","ops":[{"op":"add","lane":"side","view":"shell.tasks","index":0}]}""")
        assertEquals(true, receipt["ok"]!!.jsonPrimitive.boolean)
        assertEquals("Side area: added Background tasks", receipt["summary"]!!.jsonPrimitive.content)
        assertEquals(listOf(ModuleSlot(ShellViews.TASKS), ModuleSlot(ShellViews.SIDE_PANE)), store.config.value.sideLane())

        val refused = error("""{"action":"apply","ops":[{"op":"add","lane":"main","view":"shell.tasks","object":"conv-a"}]}""")
        assertEquals(listOf("object_not_allowed"), refused["errors"]!!.jsonArray.map { it.jsonObject["rule"]!!.jsonPrimitive.content })
    }

    @Test
    fun undoAndResetGoThroughTheHistory() = runTest {
        success(recentsFirst)
        success("""{"action":"apply","ops":[{"op":"add","lane":"side","view":"shell.canvas","object":"canvas-board","index":0}]}""")
        assertEquals(ModuleSlot(ShellViews.CANVAS, "canvas-board"), store.config.value.sideLane().first())

        val undone = success("""{"action":"undo"}""")
        assertEquals(false, undone["user_can_undo"]!!.jsonPrimitive.boolean)
        assertEquals(listOf(ModuleSlot(ShellViews.SIDE_PANE)), store.config.value.sideLane())

        val reset = success("""{"action":"reset"}""")
        assertEquals(true, reset["applied"]!!.jsonPrimitive.boolean)
        assertEquals(ShellLayoutConfig.Default, store.config.value)
        assertEquals(false, success("""{"action":"reset"}""")["applied"]!!.jsonPrimitive.boolean)
        success("""{"action":"undo"}""")
        assertEquals(HomeViews.RECENTS, store.config.value.homeLane().first().view, "reset was undoable")
    }

    @Test
    fun aValidEditThatChangesNothingIsNotACommit() = runTest {
        val receipt = success("""{"action":"apply","ops":[{"op":"move","lane":"panel","view":"panel.settings"}]}""")
        assertEquals(false, receipt["applied"]!!.jsonPrimitive.boolean)
        assertTrue(store.history.isEmpty())
        assertEquals(PanelViews.Default, store.config.value.panelLane().map { it.view })
    }

    @Test
    fun withNoShellOnScreenEveryActionSaysSo() = runTest {
        port.detach(store)
        listOf("""{"action":"get"}""", recentsFirst, """{"action":"undo"}""", """{"action":"reset"}""").forEach { input ->
            assertTrue("No app shell" in error(input)["error"]!!.jsonPrimitive.content, input)
        }
        assertEquals(ShellLayoutConfig.Default, store.config.value)
    }

    @Test
    fun theNewestAttachedShellIsTheOneChanged() = runTest {
        val other = ShellLayoutConfigStore(InMemorySecureSettingsStore(), "shell.other.lanes")
        port.attach(other)
        success(recentsFirst)
        assertEquals(ShellLayoutConfig.Default, store.config.value)
        assertEquals(HomeViews.RECENTS, other.config.value.homeLane().first().view)
        port.detach(other)
        assertEquals(store, port.attached.value)
        assertEquals(listOf(ShellLanes.HOME), ShellLayoutEdits.changes(ShellLayoutConfig.Default, other.config.value).map { it.lane })
    }
}
