package com.letta.mobile.data.meridian

import com.letta.mobile.data.canvas.CanvasToolContract
import com.letta.mobile.data.controller.extras.ExternalToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-jna0o.3 parity: every command reaches the same registry entry, with the same input
 * and the same caller, as the native tool call it replaces, and prints that call's result byte for
 * byte. Together the cases cover every tool the host can invoke, so none is left native-only.
 */
class MeridianParityTest {
    /** One command and the native call it stands for. */
    private data class Case(val argv: List<String>, val stdin: String?, val tool: String, val nativeInput: String)

    private val composeInput = """{"items":[{"kind":"NOTE","markdown":"Hello"}]}"""

    private val cases: List<Case> = listOf(
        Case(listOf("canvas", "create", "--title", "Plans"), null, CanvasToolContract.CREATE, """{"title":"Plans"}"""),
        Case(listOf("canvas", "list"), null, CanvasToolContract.LIST, "{}"),
        Case(listOf("canvas", "compose"), composeInput, CanvasToolContract.COMPOSE, composeInput),
        Case(
            listOf("canvas", "apply-ops", "--dry-run"),
            """{"ops":[${MeridianTestHost.SHAPE_OP}]}""",
            CanvasToolContract.APPLY_OPS,
            """{"ops":[${MeridianTestHost.SHAPE_OP}],"dry_run":true}""",
        ),
        Case(listOf("canvas", "apply-ops"), """{"ops":[${MeridianTestHost.SHAPE_OP}]}""", CanvasToolContract.APPLY_OPS, """{"ops":[${MeridianTestHost.SHAPE_OP}]}"""),
        Case(listOf("canvas", "scene"), null, CanvasToolContract.GET_SCENE, "{}"),
        Case(listOf("canvas", "layout", "--limit", "5"), null, CanvasToolContract.GET_LAYOUT, """{"limit":5}"""),
        Case(
            listOf("canvas", "replace-scene", "--dry-run=true"),
            """{"scene_json":"{\"bgColor\":\"#ffffffff\",\"elements\":[]}"}""",
            CanvasToolContract.REPLACE_SCENE,
            """{"scene_json":"{\"bgColor\":\"#ffffffff\",\"elements\":[]}","dry_run":true}""",
        ),
        Case(listOf("canvas", "guide"), null, CanvasToolContract.COMPOSE_GUIDE, "{}"),
        Case(listOf("guide", "compose"), null, CanvasToolContract.COMPOSE_GUIDE, "{}"),
        Case(
            listOf("canvas", "preview"),
            """{"width_px":393,"height_px":852,"density":3.0}""",
            CanvasToolContract.RENDER_PREVIEW,
            """{"width_px":393,"height_px":852,"density":3.0}""",
        ),
        Case(listOf("agents", "find", "Bob"), null, "agent_discover", """{"query":"Bob"}"""),
        Case(listOf("agent-message", "send", "agent-bob", "--body", "hi there"), null, "agent_message_send", """{"to":"agent-bob","body":"hi there"}"""),
        Case(listOf("plugin", "letta.example", "start"), """{"label":"x"}""", "example_start", """{"label":"x"}"""),
        Case(listOf("tool", "device_action"), """{"action":"device.catalog"}""", "device_action", """{"action":"device.catalog"}"""),
    )

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    /** The one thing two hosts answer differently: the random ids they mint (canvas_create, op ids). */
    private fun String.withoutMintedIds(): String = replace(MINTED_ID, "<minted>")

    private companion object {
        val MINTED_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }

    @Test
    fun everyCommandRunsTheNativeToolsRegistryEntryWithTheSameInputAndCaller() = runTest {
        val viaRouter = MeridianTestHost()
        val native = MeridianTestHost()
        val router = viaRouter.router()
        cases.forEach { case ->
            val response = router.execute(MeridianRequest(case.argv, case.stdin, MeridianTestHost.CALLER))
            val nativeResult = native.registry.invoke(case.tool, json(case.nativeInput), MeridianTestHost.CALLER)

            val routed = viaRouter.calls.last()
            val direct = native.calls.last()
            assertEquals(direct, routed, "${case.argv} reaches ${case.tool} as the native call does")
            assertEquals(case.tool, routed.tool)
            val expected = when (nativeResult) {
                is ExternalToolResult.Success -> MeridianResponse(MeridianExit.OK, nativeResult.content.withoutMintedIds())
                is ExternalToolResult.Error -> error("${case.tool} refused the native call: ${nativeResult.error}")
            }
            assertEquals(expected, response.copy(stdout = response.stdout.withoutMintedIds()), "${case.argv} prints the native result byte for byte")
        }
    }

    @Test
    fun theCasesCoverEveryToolTheHostCanInvoke() {
        val invocable = MeridianTestHost().registry.invocableTools().map { it.name }.toSet()
        assertEquals(invocable, cases.map { it.tool }.toSet(), "every advertised tool has a command")
        val commands = MeridianTestHost().router().commands().map { it.toolName }.toSet()
        assertEquals(invocable, commands)
    }

    @Test
    fun theMetaToolsCommandStringRunsTheSameCall() = runTest {
        val host = MeridianTestHost()
        val response = host.router().execute("meridian canvas layout --limit 5 --canvas '' ", null, MeridianTestHost.CALLER)
        assertEquals(MeridianExit.OK, response.exitCode, response.stdout)
        assertEquals(json("""{"limit":5,"canvas_id":""}"""), host.calls.single().input)
        assertTrue(response.stdout.startsWith("{"))

        val compose = host.router().execute("canvas compose", json(composeInput), MeridianTestHost.CALLER)
        assertEquals(MeridianExit.OK, compose.exitCode, compose.stdout)
        assertEquals(json(composeInput), host.calls.last().input)
    }
}
