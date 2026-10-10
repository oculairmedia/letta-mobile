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
 * letta-mobile-jna0o.6: golden command shapes for the `meridian` recogniser. Each shape names the
 * native canvas tool and the arguments it would have been called with, or falls back (null).
 */
class MeridianCommandCallTest {
    private val input = """{"title":"Weekend plan","items":[{"kind":"NOTE","key":"n1","text":"Hi"}]}"""

    // --- Bash: stdin forms --------------------------------------------------------------

    @Test
    fun quotedHeredocIsTheComposeInput() {
        val call = bash("meridian canvas compose <<'JSON'\n$input\nJSON")
        assertEquals(MeridianCommandCall(CanvasToolContract.COMPOSE, input), call)
    }

    @Test
    fun prettyPrintedHeredocIsReadAsTheSameCompactObject() {
        val pretty = "{\n  \"title\": \"Weekend plan\",\n  \"items\": [\n    {\"kind\": \"NOTE\", \"key\": \"n1\", \"text\": \"Hi\"}\n  ]\n}"
        assertEquals(input, bash("meridian canvas compose <<JSON\n$pretty\nJSON")!!.arguments)
    }

    @Test
    fun tabStrippedHeredocAndTrailingCommandsAreRead() {
        val call = bash("meridian canvas compose <<-EOF && echo done\n\t$input\n\tEOF\n")
        assertEquals(input, call!!.arguments)
    }

    @Test
    fun hereStringIsTheInput() {
        assertEquals(input, bash("meridian canvas compose <<< '$input'")!!.arguments)
    }

    @Test
    fun echoAndPrintfPipedInAreTheInput() {
        assertEquals(input, bash("echo '$input' | meridian canvas compose")!!.arguments)
        assertEquals(input, bash("printf '%s' '$input' | meridian canvas compose")!!.arguments)
    }

    @Test
    fun catHeredocPipedInIsTheInput() {
        assertEquals(input, bash("cat <<'EOF' | meridian canvas compose --dry-run\n$input\nEOF")!!.arguments.let {
            it.replace(""","dry_run":true}""", "}")
        })
    }

    @Test
    fun fileInputLeavesOnlyTheFlags() {
        assertEquals("""{"canvas_id":"c1"}""", bash("meridian canvas compose --canvas c1 < /tmp/input.json")!!.arguments)
        assertEquals("{}", bash("meridian canvas compose --input-file /tmp/input.json")!!.arguments)
        assertEquals("{}", bash("cat /tmp/input.json | meridian canvas compose")!!.arguments)
    }

    @Test
    fun inlineQuotedJsonFlagIsTheInput() {
        assertEquals(input, bash("meridian canvas compose --input '$input'")!!.arguments)
        assertEquals(input, bash("meridian canvas compose --json=\"${input.replace("\"", "\\\"")}\"")!!.arguments)
    }

    // --- Bash: wrappers, prefixes, chains -----------------------------------------------

    @Test
    fun bashLcWrapperIsUnwrapped() {
        val script = "bash -lc 'meridian canvas layout --limit 50'"
        assertEquals(MeridianCommandCall(CanvasToolContract.GET_LAYOUT, """{"limit":50}"""), bash(script))
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
        val call = bash("LETTA_TOKEN=s3cret FOO=1 env /usr/local/bin/meridian canvas list")
        assertEquals(MeridianCommandCall(CanvasToolContract.LIST, "{}"), call)
    }

    @Test
    fun chainedCommandsAroundOneInvocationAreRead() {
        val call = bash("cd /tmp && meridian canvas apply-ops --dry-run <<'OPS' | jq .\n{\"ops\":[]}\nOPS\necho ok")
        assertEquals(MeridianCommandCall(CanvasToolContract.APPLY_OPS, """{"ops":[],"dry_run":true}"""), call)
    }

    @Test
    fun flagsMergeWithoutOverridingTheInput() {
        val call = bash("meridian canvas compose --canvas=c2 --dry-run <<'J'\n{\"canvas_id\":\"c1\"}\nJ")
        assertEquals("""{"canvas_id":"c1","dry_run":true}""", call!!.arguments)
    }

    @Test
    fun verbsMapToTheirNativeTools() {
        assertEquals(CanvasToolContract.CREATE, bash("meridian canvas create --title \"My board\"")!!.toolName)
        assertEquals("""{"title":"My board"}""", bash("meridian canvas create --title \"My board\"")!!.arguments)
        assertEquals(CanvasToolContract.COMPOSE_GUIDE, bash("meridian canvas guide compose")!!.toolName)
        assertEquals(CanvasToolContract.REPLACE_SCENE, bash("meridian canvas replace_scene < s.json")!!.toolName)
        assertEquals("""{"cursor":"abc"}""", bash("meridian canvas layout --cursor abc")!!.arguments)
    }

    // --- fallbacks: stay a Bash row ------------------------------------------------------

    @Test
    fun helpSchemaAndUnknownCommandsFallBack() {
        assertNull(bash("meridian --help"))
        assertNull(bash("meridian canvas --help"))
        assertNull(bash("meridian canvas compose --help"))
        assertNull(bash("meridian canvas schema compose"))
        assertNull(bash("meridian canvas ops --help"))
        assertNull(bash("meridian canvas guide ops"))
        assertNull(bash("meridian agents find --name x"))
        assertNull(bash("meridian canvas"))
    }

    @Test
    fun malformedOrAmbiguousCommandsFallBack() {
        assertNull(bash("meridian canvas compose --input '{\"unterminated"))
        assertNull(bash("meridian canvas layout && meridian canvas scene"))
        assertNull(bash("echo meridian canvas layout"))
        assertNull(bash("grep -r meridian ."))
        assertNull(MeridianCommandCall.parse("Bash", "{\"command\": \"meridian canvas lay"))
        assertNull(MeridianCommandCall.parse("Bash", "not json meridian"))
        assertNull(MeridianCommandCall.parse("Bash", """{"command": 42, "meridian": true}"""))
        assertNull(MeridianCommandCall.parse("Read", bashArgs("meridian canvas layout")))
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

    // --- the meta-tool -------------------------------------------------------------------

    @Test
    fun metaToolCommandAndObjectInput() {
        val args = """{"command":"canvas compose","input":$input}"""
        assertEquals(MeridianCommandCall(CanvasToolContract.COMPOSE, input), MeridianCommandCall.parse("meridian", args))
    }

    @Test
    fun metaToolStringInputArgvAndPrefixedCommand() {
        val stringInput = buildJsonObject { put("command", "meridian canvas compose --dry-run"); put("input", input) }.toString()
        assertEquals("""{"title":"Weekend plan","items":[{"kind":"NOTE","key":"n1","text":"Hi"}],"dry_run":true}""", MeridianCommandCall.parse("meridian", stringInput)!!.arguments)
        val argv = """{"command":["canvas","layout","--limit","5"]}"""
        assertEquals(MeridianCommandCall(CanvasToolContract.GET_LAYOUT, """{"limit":5}"""), MeridianCommandCall.parse("meridian", argv))
        assertNull(MeridianCommandCall.parse("meridian", """{"command":"canvas --help"}"""))
        assertNull(MeridianCommandCall.parse("meridian", """{"input":{}}"""))
    }

    // --- stdout --------------------------------------------------------------------------

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
    fun errorResultsAreRecognised() {
        assertEquals(true, MeridianCommandCall.isErrorResult("""{"error":"host_unavailable"}"""))
        assertEquals(true, MeridianCommandCall.isErrorResult("""{"ok":false,"code":"invalid"}"""))
        assertEquals(false, MeridianCommandCall.isErrorResult("""{"ok":true,"artifact_id":"a"}"""))
        assertEquals(false, MeridianCommandCall.isErrorResult("""{"error":null}"""))
        assertEquals(false, MeridianCommandCall.isErrorResult("not json"))
    }

    private fun bash(script: String): MeridianCommandCall? = MeridianCommandCall.parse("Bash", bashArgs(script))

    private fun bashArgs(script: String): String =
        buildJsonObject { put("command", script); put("description", "draw") }.toString()
}
