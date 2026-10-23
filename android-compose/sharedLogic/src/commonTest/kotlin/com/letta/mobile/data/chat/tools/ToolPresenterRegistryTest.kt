package com.letta.mobile.data.chat.tools

import com.letta.mobile.data.chat.tools.bundled.BundledToolPresenters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** letta-mobile-2ccpv: which presenter a tool call reads through, and how the fallback reads it. */
class ToolPresenterRegistryTest {
    private fun call(name: String, arguments: String = "{}", result: String? = null) = ToolCallFacts(name, arguments, result)

    private fun presenter(id: String, vararg names: String, requiredArgs: Set<String> = emptySet()) = object : ToolPresenter {
        override val id = id
        override val matcher = ToolMatcher(names.toList(), requiredArgs)
        override fun line(call: ToolCallFacts, scope: ToolPresenterScope) = ToolStepLine(ToolVerb.Read, presenterId = id)
        override fun inset(call: ToolCallFacts, scope: ToolPresenterScope): ToolInset? = null
    }

    @Test
    fun anExactNameBeatsAGlobAndALongerGlobBeatsAShorterOne() {
        val registry = ToolPresenterRegistry.Empty
            .register(presenter("exact", "memory_replace"))
            .register(presenter("short", "mem*"))
            .register(presenter("long", "memory_*"))
        assertEquals("exact", registry.presenterFor(call("memory_replace")).id)
        assertEquals("long", registry.presenterFor(call("memory_insert")).id)
        assertEquals("short", registry.presenterFor(call("memo")).id)
    }

    @Test
    fun namesMatchIgnoringCaseAndAnArgShapeNarrowsTheClaim() {
        val registry = ToolPresenterRegistry.Empty.register(presenter("shaped", "Read", requiredArgs = setOf("file_path")))
        assertEquals("shaped", registry.presenterFor(call("read", """{"file_path":"a.kt"}""")).id)
        assertEquals(GenericToolPresenter.id, registry.presenterFor(call("read", """{"path":"a.kt"}""")).id)
    }

    @Test
    fun aLaterRegistrationOfTheSameIdReplacesTheEarlierOne() {
        val registry = ToolPresenterRegistry.Empty.register(presenter("p", "a")).register(presenter("p", "b"))
        assertEquals(listOf("p"), registry.ids)
        assertEquals(GenericToolPresenter.id, registry.presenterFor(call("a")).id)
    }

    @Test
    fun aContributedPresenterTakesOnlyWhatNothingBundledClaims() {
        val spec = ToolPresenterSpec(tools = listOf("Bash", "fetch_issue"), verb = ToolVerb.Fetched)
        val registry = BundledToolPresenters.registry.contribute("acme.issues", mapOf("issues" to spec))
        assertEquals("bash", registry.presenterFor(call("Bash")).id)
        assertEquals("acme.issues/issues", registry.presenterFor(call("fetch_issue")).id)
    }

    @Test
    fun anUnknownToolReadsAsCalledWithItsArgumentsAndResult() {
        val facts = call("fetch_issue", """{"id":"LM-12","labels":["a","b"],"none":null}""", result = "open")
        val registry = BundledToolPresenters.registry
        val line = registry.line(facts)
        assertEquals(ToolVerb.Called, line.verb)
        assertEquals("fetch_issue", line.subject)
        assertNull(line.failure)
        val inset = assertIs<ToolInset.Arguments>(registry.inset(facts))
        assertEquals(listOf(ToolArgumentField("id", "LM-12"), ToolArgumentField("labels", """["a","b"]""")), inset.fields)
        assertEquals("open", inset.result)
    }

    @Test
    fun argumentsThatAreNotAnObjectReadAsTheirRawText() {
        val inset = assertIs<ToolInset.Arguments>(GenericToolPresenter.argumentsInset(call("x", "not json")))
        assertEquals(listOf(ToolArgumentField(GenericToolPresenter.RAW_ARGUMENTS, "not json")), inset.fields)
    }

    @Test
    fun theFallbackOpensTheFileACallNamesAndOnlyTheMemoryPresentersOpenMemory() {
        val registry = BundledToolPresenters.registry
        val file = registry.line(call("open_thing", """{"file_path":"/repo/a.kt"}""")).previews
        assertEquals(listOf(ToolPreviewTarget.File("/repo/a.kt")), file)
        // A memory-sounding tool no memory presenter claims is not routed to the Memory view.
        val unknown = registry.line(call("memory_view", """{"path":"system/human.md"}"""))
        assertEquals("generic", unknown.presenterId)
        assertEquals(listOf(ToolPreviewTarget.File("system/human.md")), unknown.previews)
        val memory = registry.line(call("memory", """{"command":"view","path":"system/human.md"}""")).previews
        assertTrue(memory.all { it is ToolPreviewTarget.Memory }, "$memory")
        assertEquals(emptyList(), registry.line(call("x", """{"command":"ls"}""")).previews)
    }

    @Test
    fun aSpecReadsItsTargetInsetAndPreviewFromTheArgumentsItNames() {
        val spec = ToolPresenterSpec(
            tools = listOf("view_lines"),
            verb = ToolVerb.Read,
            target = "file",
            inset = ToolInsetSpec(ToolInsetKind.BeforeAfter, before = "old", after = "new"),
            preview = ToolPreviewSpec(ToolPreviewKind.File, arg = "file", startLine = "from", lineCount = "count"),
        )
        val registry = ToolPresenterRegistry.Empty.contribute("p", mapOf("v" to spec))
        val facts = call("view_lines", """{"file":"src/a.kt","from":3,"count":"2","old":"x","new":"y"}""")
        val line = registry.line(facts)
        assertEquals("src/a.kt", line.target)
        assertEquals(listOf(ToolPreviewTarget.File("src/a.kt", 3..4)), line.previews)
        assertEquals(ToolInset.BeforeAfter("x", "y"), registry.inset(facts))
    }

    @Test
    fun onlyANonZeroExitReportedAtTheEndOfAResultIsAFailure() {
        assertEquals(ToolFailure(2), ToolArguments.exitFailure("boom\n\nExit code: 2"))
        assertNull(ToolArguments.exitFailure("ok\nExit code: 0"))
        assertNull(ToolArguments.exitFailure("Exit code: 3\n" + "x".repeat(ToolArguments.EXIT_SCAN_CHARS)))
        assertEquals(ToolFailure(1), ToolArguments.exitFailure("Process exited with code 1"))
    }

    @Test
    fun theApprovalPrimaryArgumentIsThePayloadElseTheArgumentsAsLines() {
        assertEquals("echo hi", ToolArguments.primary("""{"command":"echo hi","timeout":5}"""))
        assertEquals("file_path: a.kt\nold_string: x", ToolArguments.primary("""{"file_path":"a.kt","old_string":"x"}"""))
        assertEquals("not json", ToolArguments.primary("not json"))
    }

    @Test
    fun aLineNeverParsesArgumentsItsPresenterDoesNotRead() {
        val facts = call("anything", "{ not json")
        val line = ToolPresenterRegistry.Empty.register(presenter("cheap", "anything")).line(facts)
        assertEquals("cheap", line.presenterId)
        assertFalse(facts.argumentsParsed)
    }
}
