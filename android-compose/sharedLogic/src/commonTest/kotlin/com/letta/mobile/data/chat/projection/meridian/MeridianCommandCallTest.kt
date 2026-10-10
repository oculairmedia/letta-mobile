package com.letta.mobile.data.chat.projection.meridian

import com.letta.mobile.data.canvas.CanvasToolContract
import com.letta.mobile.data.model.ToolCall
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * letta-mobile-jna0o.6: golden command shapes for the `meridian` recogniser, against the host's
 * command surface (`MeridianCommandCatalog`, letta-mobile-jna0o.3). Each real command, run through
 * Bash and through the meta-tool, names the native tool and the input the router builds for it.
 */
class MeridianCommandCallTest {
    private val input = """{"title":"Weekend plan","items":[{"kind":"NOTE","key":"n1","text":"Hi"}]}"""

    /** One real command line, its stdin JSON (or null), and the native call the router makes. */
    private data class Golden(val line: String, val stdin: String?, val tool: String, val arguments: String)

    private val goldens = listOf(
        Golden("canvas compose", input, CanvasToolContract.COMPOSE, input),
        Golden("canvas compose --dry-run --canvas c1", input, CanvasToolContract.COMPOSE, input.dropLast(1) + ""","dry_run":true,"canvas_id":"c1"}"""),
        Golden("canvas guide", null, CanvasToolContract.COMPOSE_GUIDE, "{}"),
        Golden("canvas guide compose", null, CanvasToolContract.COMPOSE_GUIDE, "{}"),
        Golden("guide compose", null, CanvasToolContract.COMPOSE_GUIDE, "{}"),
        Golden("canvas layout --limit 50 --cursor r3:200", null, CanvasToolContract.GET_LAYOUT, """{"limit":50,"cursor":"r3:200"}"""),
        Golden("canvas scene --canvas-id c2", null, CanvasToolContract.GET_SCENE, """{"canvas_id":"c2"}"""),
        Golden("canvas apply-ops --dry-run", """{"ops":[]}""", CanvasToolContract.APPLY_OPS, """{"ops":[],"dry_run":true}"""),
        Golden("canvas replace-scene --dry-run=false", """{"scene_json":"{}"}""", CanvasToolContract.REPLACE_SCENE, """{"scene_json":"{}","dry_run":false}"""),
        Golden("canvas list --conversation conv-1", null, CanvasToolContract.LIST, """{"conversation_id":"conv-1"}"""),
        Golden("canvas create --title \"My board\" --conversation conv-1", null, CanvasToolContract.CREATE, """{"title":"My board","conversation_id":"conv-1"}"""),
        Golden("canvas preview --width-px 390 --height-px 844 --density 3", null, CanvasToolContract.RENDER_PREVIEW, """{"width_px":390,"height_px":844,"density":3.0}"""),
        Golden("canvas export-svg", null, CanvasToolContract.EXPORT_SVG, "{}"),
        Golden("agents find planner --limit 5", null, "agent_discover", """{"query":"planner","limit":5}"""),
        Golden("agent-message send agent-42", """{"body":"hello\nthere"}""", "agent_message_send", """{"body":"hello\nthere","to":"agent-42"}"""),
        Golden("plugin com.example.weather forecast --city Paris", null, "weather_forecast", """{"city":"Paris"}"""),
        Golden("tool memory_insert --label notes", """{"text":"x"}""", "memory_insert", """{"text":"x","label":"notes"}"""),
        Golden("tool canvas_get_scene", null, CanvasToolContract.GET_SCENE, "{}"),
    )

    // --- every real command, both front doors -----------------------------------------------

    @Test
    fun everyCommandThroughBashNamesTheNativeCall() {
        goldens.forEach { golden ->
            val script = "meridian ${golden.line}" + (golden.stdin?.let { " <<'JSON'\n$it\nJSON" } ?: "")
            assertEquals(MeridianCommandCall(golden.tool, golden.arguments), bash(script), golden.line)
        }
    }

    @Test
    fun everyCommandThroughTheMetaToolNamesTheNativeCall() {
        goldens.forEach { golden ->
            assertEquals(MeridianCommandCall(golden.tool, golden.arguments), meta(golden.line, golden.stdin), golden.line)
            assertEquals(MeridianCommandCall(golden.tool, golden.arguments), meta("meridian ${golden.line}", golden.stdin), golden.line)
        }
    }

    // --- Bash: stdin forms ------------------------------------------------------------------

    @Test
    fun prettyPrintedHeredocIsReadAsTheSameCompactObject() {
        val pretty = "{\n  \"title\": \"Weekend plan\",\n  \"items\": [\n    {\"kind\": \"NOTE\", \"key\": \"n1\", \"text\": \"Hi\"}\n  ]\n}"
        assertEquals(input, bash("meridian canvas compose <<JSON\n$pretty\nJSON")!!.arguments)
    }

    @Test
    fun tabStrippedHeredocAndTrailingCommandsAreRead() {
        assertEquals(input, bash("meridian canvas compose <<-EOF && echo done\n\t$input\n\tEOF\n")!!.arguments)
    }

    @Test
    fun hereStringEchoPrintfAndCatPipesAreTheInput() {
        assertEquals(input, bash("meridian canvas compose <<< '$input'")!!.arguments)
        assertEquals(input, bash("echo '$input' | meridian canvas compose")!!.arguments)
        assertEquals(input, bash("printf '%s' '$input' | meridian canvas compose")!!.arguments)
        assertEquals(input, bash("cat <<'EOF' | meridian canvas compose\n$input\nEOF")!!.arguments)
    }

    @Test
    fun fileInputLeavesOnlyTheFlags() {
        assertEquals("""{"canvas_id":"c1"}""", bash("meridian canvas compose --canvas c1 < /tmp/input.json")!!.arguments)
        assertEquals("{}", bash("meridian canvas compose --input-file /tmp/input.json")!!.arguments)
        assertEquals("{}", bash("cat /tmp/input.json | meridian canvas compose")!!.arguments)
    }

    // --- Bash: wrappers, prefixes, chains ---------------------------------------------------

    @Test
    fun bashLcWrapperIsUnwrapped() {
        assertEquals(MeridianCommandCall(CanvasToolContract.GET_LAYOUT, """{"limit":50}"""), bash("bash -lc 'meridian canvas layout --limit 50'"))
    }

    @Test
    fun argvWrapperIsUnwrapped() {
        val args = buildJsonObject {
            put("cmd", buildJsonArray { add(JsonPrimitive("/bin/bash")); add(JsonPrimitive("-lc")); add(JsonPrimitive("meridian canvas scene")) })
        }.toString()
        assertEquals(CanvasToolContract.GET_SCENE, MeridianCommandCall.parse("exec_command", args)!!.toolName)
    }

    @Test
    fun envPrefixesAndPathsAreDroppedFromTheRow() {
        assertEquals(MeridianCommandCall(CanvasToolContract.LIST, "{}"), bash("LETTA_TOKEN=s3cret FOO=1 env /usr/local/bin/meridian canvas list"))
    }

    @Test
    fun chainedCommandsAroundOneInvocationAreRead() {
        val call = bash("cd /tmp && meridian canvas apply-ops --dry-run <<'OPS' | jq .\n{\"ops\":[]}\nOPS\necho ok")
        assertEquals(MeridianCommandCall(CanvasToolContract.APPLY_OPS, """{"ops":[],"dry_run":true}"""), call)
    }

    @Test
    fun stdinKeepsAFieldTheFlagsRepeat() {
        // The router refuses a property given twice with different values; the row shows stdin's.
        assertEquals("""{"canvas_id":"c1","dry_run":true}""", bash("meridian canvas compose --canvas=c2 --dry-run <<'J'\n{\"canvas_id\":\"c1\"}\nJ")!!.arguments)
    }

    // --- fallbacks: stay a Bash (or meridian) row -------------------------------------------

    @Test
    fun helpGuidesAndSchemasRunNoToolAndFallBack() {
        listOf(
            "meridian", "meridian --help", "meridian help", "meridian help canvas", "meridian canvas --help",
            "meridian canvas compose --help", "meridian canvas compose -h", "meridian guide", "meridian guide ops",
            "meridian schema canvas compose", "meridian canvas schema compose", "meridian canvas", "meridian agents",
            "meridian plugin com.example.weather", "meridian tool", "meridian tool meridian",
        ).forEach { assertNull(bash(it), it) }
        assertNull(meta("--help", null))
        assertNull(meta("schema canvas compose", null))
    }

    @Test
    fun verbsAndFlagsTheRouterDoesNotHaveAreNotTools() {
        assertNull(bash("meridian canvas get-layout"))
        assertNull(bash("meridian canvas get-scene"))
        assertNull(bash("meridian canvas render-preview"))
        assertNull(bash("meridian canvas ops"))
        // Not router flags: `-f` is a positional (which canvas compose does not take), `--input` an unknown property.
        assertEquals("{}", bash("meridian canvas compose -f x.json")!!.arguments)
    }

    @Test
    fun malformedOrAmbiguousCommandsFallBack() {
        assertNull(bash("meridian canvas compose --title '{\"unterminated"))
        assertNull(bash("meridian canvas layout && meridian canvas scene"))
        assertNull(bash("echo meridian canvas layout"))
        assertNull(bash("grep -r meridian ."))
        assertNull(MeridianCommandCall.parse("Bash", "{\"command\": \"meridian canvas lay"))
        assertNull(MeridianCommandCall.parse("Bash", "not json meridian"))
        assertNull(MeridianCommandCall.parse("Bash", """{"command": 42, "meridian": true}"""))
        assertNull(MeridianCommandCall.parse("Read", bashArgs("meridian canvas layout")))
        assertNull(MeridianCommandCall.parse("meridian", """{"input":{}}"""))
        assertNull(MeridianCommandCall.parse("meridian", """{"command":["canvas","layout"]}"""))
    }

    @Test
    fun aMetaToolInputThatIsNotAnObjectIsNotTheInput() {
        // The meta-tool refuses it (input must be one JSON object); the row shows the flags alone.
        val args = buildJsonObject { put("command", "canvas compose --dry-run"); put("input", input) }.toString()
        assertEquals("""{"dry_run":true}""", MeridianCommandCall.parse("meridian", args)!!.arguments)
    }

    @Test
    fun malformedHeredocBodyIsKeptAsTheRawInput() {
        // The receipt degrades from it like a native call with malformed arguments.
        assertEquals("{\"title\": oops", bash("meridian canvas compose <<'J'\n{\"title\": oops\nJ")!!.arguments)
    }

    @Test
    fun unterminatedHeredocTakesTheRest() {
        assertEquals(input, bash("meridian canvas compose <<'JSON'\n$input")!!.arguments)
    }

    @Test
    fun nonMeridianCallsAreReturnedUnchanged() {
        val call = ToolCall(id = "t1", name = "Bash", arguments = bashArgs("ls -la"))
        assertSame(call, MeridianCommandCall.canonical(call))
    }

    // --- stdout and router errors -----------------------------------------------------------

    @Test
    fun stdoutJsonIsReadOutOfShellFraming() {
        val receipt = """{"ok":true,"artifact_id":"a1"}"""
        assertEquals(receipt, MeridianCommandCall.stdoutJson(receipt))
        assertEquals("  $receipt\n", MeridianCommandCall.stdoutJson("  $receipt\n"))
        assertEquals(receipt, MeridianCommandCall.stdoutJson("Exit code: 0\n$receipt\nhint: done"))
        assertEquals(receipt, MeridianCommandCall.stdoutJson("$receipt\nhint: retry with {\"dry_run\": true}"))
        val nested = """{"ok":true,"note":"a } in a string","items":[{"k":1}]}"""
        assertEquals(nested, MeridianCommandCall.stdoutJson("Exit code: 0\n$nested\n}"))
        assertEquals("plain text", MeridianCommandCall.stdoutJson("plain text"))
        assertNull(MeridianCommandCall.stdoutJson(null))
    }

    @Test
    fun aRouterRefusalReadsAsTheToolsOwnRefusal() {
        val refusal = """{"ok":false,"code":"VALIDATION_FAILED","problems":[]}"""
        assertEquals(refusal, MeridianCommandCall.toolResult(MeridianCliFixtures.refused(refusal)))
        assertEquals("Unauthorized: actor x", MeridianCommandCall.toolResult(MeridianCliFixtures.deniedMessage("Unauthorized: actor x")))
        assertEquals(MeridianCliFixtures.HOST_UNAVAILABLE_JSON, MeridianCommandCall.toolResult(MeridianCliFixtures.HOST_UNAVAILABLE))
        assertEquals(MeridianCliFixtures.USAGE_JSON, MeridianCommandCall.toolResult(MeridianCliFixtures.USAGE))
        assertEquals("""{"rows":[]}""", MeridianCommandCall.toolResult("""{"rows":[]}"""))
    }

    @Test
    fun errorResultsAreRecognised() {
        assertEquals(true, MeridianCommandCall.isErrorResult(MeridianCliFixtures.HOST_UNAVAILABLE))
        assertEquals(true, MeridianCommandCall.isErrorResult(MeridianCliFixtures.refused("""{"ok":false}""")))
        assertEquals(true, MeridianCommandCall.isErrorResult("""{"ok":false,"code":"invalid"}"""))
        assertEquals(false, MeridianCommandCall.isErrorResult("""{"ok":true,"artifact_id":"a"}"""))
        assertEquals(false, MeridianCommandCall.isErrorResult("""{"error":null}"""))
        assertEquals(false, MeridianCommandCall.isErrorResult("not json"))
    }

    private fun bash(script: String): MeridianCommandCall? = MeridianCommandCall.parse("Bash", bashArgs(script))

    private fun meta(command: String, stdin: String?): MeridianCommandCall? {
        val args = buildJsonObject {
            put("command", command)
            stdin?.let { put("input", kotlinx.serialization.json.Json.parseToJsonElement(it)) }
        }.toString()
        return MeridianCommandCall.parse(MeridianCommandCall.META_TOOL, args)
    }

    private fun bashArgs(script: String): String =
        buildJsonObject { put("command", script); put("description", "draw") }.toString()
}
