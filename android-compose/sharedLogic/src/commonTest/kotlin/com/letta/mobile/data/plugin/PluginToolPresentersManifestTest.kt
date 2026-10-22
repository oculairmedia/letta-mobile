package com.letta.mobile.data.plugin

import com.letta.mobile.data.chat.tools.CommandPresenterSpec
import com.letta.mobile.data.chat.tools.CommandPreviewSpec
import com.letta.mobile.data.chat.tools.ToolInsetKind
import com.letta.mobile.data.chat.tools.ToolPreviewKind
import com.letta.mobile.data.chat.tools.ToolVerb
import com.letta.mobile.data.plugin.PluginRegistryFixtures.exampleDocument
import com.letta.mobile.data.plugin.PluginRegistryFixtures.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** letta-mobile-2ccpv: a manifest contributes tool and command presenters, held to the schema and its rules. */
class PluginToolPresentersManifestTest {
    private fun parse(path: String, value: String): PluginManifestResult =
        PluginManifestParser.parse(JsonPatch.set(exampleDocument, path, json(value)))

    private fun refusedAt(path: String, value: String): List<String> =
        assertIs<PluginManifestResult.Refused>(parse(path, value)).problems.map { "${it.path} ${it.code}" }

    @Test
    fun aToolPresenterParsesIntoItsSpec() {
        val parsed = assertIs<PluginManifestResult.Parsed>(
            parse(
                "/toolPresenters",
                """{"issue":{"tools":["fetch_issue","issue_*"],"requiredArgs":["id"],"verb":"fetched","target":"id",
                   "inset":{"kind":"text"},"preview":{"kind":"file","arg":"path","startLine":"line"}}}""",
            ),
        )
        val spec = parsed.manifest.toolPresenters.getValue("issue")
        assertEquals(listOf("fetch_issue", "issue_*"), spec.tools)
        assertEquals(ToolVerb.Fetched, spec.verb)
        assertEquals(ToolInsetKind.Text, spec.inset?.kind)
        assertEquals(ToolPreviewKind.File, spec.preview?.kind)
    }

    @Test
    fun aCommandPresenterParsesIntoItsSpec() {
        val parsed = assertIs<PluginManifestResult.Parsed>(
            parse(
                "/commandPresenters",
                """{"deploy":{"program":"acme","subcommands":["deploy"],"preview":{"kind":"task","flag":"--job"}}}""",
            ),
        )
        assertEquals(
            CommandPresenterSpec("acme", listOf("deploy"), ToolVerb.Ran, CommandPreviewSpec(ToolPreviewKind.Task, "--job")),
            parsed.manifest.commandPresenters.getValue("deploy"),
        )
    }

    @Test
    fun anUnknownVerbIsRefusedByTheSchema() {
        val refused = refusedAt("/toolPresenters", """{"x":{"tools":["a"],"verb":"exploded"}}""")
        assertEquals(listOf("/toolPresenters/x/verb NOT_ALLOWED"), refused)
    }

    @Test
    fun anInsetNamesTheArgumentsItsKindReads() {
        assertEquals(
            listOf("/toolPresenters/x/inset/command BAD_TOOL_PRESENTER"),
            refusedAt("/toolPresenters", """{"x":{"tools":["a"],"inset":{"kind":"command"}}}"""),
        )
        assertEquals(
            listOf("/toolPresenters/x/inset/before BAD_TOOL_PRESENTER"),
            refusedAt("/toolPresenters", """{"x":{"tools":["a"],"inset":{"kind":"before_after"}}}"""),
        )
    }

    @Test
    fun aToolNamedTwiceIsADuplicate() {
        assertEquals(listOf("/toolPresenters/x/tools/1 DUPLICATE"), refusedAt("/toolPresenters", """{"x":{"tools":["a","a"]}}"""))
    }
}
