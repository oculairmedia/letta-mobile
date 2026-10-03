package com.letta.mobile.data.plugin

import com.letta.mobile.data.plugin.PluginRegistryFixtures.exampleDocument
import com.letta.mobile.data.plugin.PluginRegistryFixtures.json
import com.letta.mobile.data.plugin.PluginRegistryFixtures.manifest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The strict `letta-plugin.json` contract (plan section 3.1, letta-mobile-s416w.24). The fixture
 * cases in `manifest-problems.json` cover one refusal per rule (PluginManifestFixturesTest); these
 * cover the parsed model and the rules a fixture file cannot hold comfortably.
 */
class PluginManifestTest {
    private fun refusedAt(document: kotlinx.serialization.json.JsonElement): List<String> =
        assertIs<PluginManifestResult.Refused>(PluginManifestParser.parse(document)).problems.map { "${it.path} ${it.code}" }

    @Test
    fun theExampleParsesIntoTheTypedModel() {
        val example = PluginRegistryFixtures.example
        assertEquals("example", example.idShort)
        assertEquals(PluginRuntime.Jvm("plugin.jar", "com.example.ExamplePlugin"), example.runtime)
        assertEquals(listOf(PluginCapability.CANVAS_PLACE, PluginCapability.CANVAS_READ, PluginCapability.ASSETS_WRITE, PluginCapability.NET_CONNECT, PluginCapability.UI_PAGES), example.capabilities)
        assertEquals(listOf("widget"), example.elements.keys.toList())
        assertEquals("ext:letta.example/widget", example.elementType("widget"))
        assertEquals("example_start", example.toolName("start"))
        assertEquals(listOf("start", "cancel", "refresh"), example.actions.keys.toList())
        assertEquals(PluginSettingType.INTEGER, example.settings.getValue("quality").type)
        assertEquals(JsonPrimitive(80), example.settings.getValue("quality").default)
        assertEquals(listOf(PluginDisplayMode.INLINE, PluginDisplayMode.FULLSCREEN), example.pages.getValue("widget").displayModes)
    }

    @Test
    fun textThatIsNotJsonIsRefusedAtTheRoot() {
        val refused = assertIs<PluginManifestResult.Refused>(PluginManifestParser.parse("{ not json"))
        assertEquals(listOf(" SYNTAX"), refused.problems.map { "${it.path} ${it.code}" })
        assertEquals(listOf(" WRONG_TYPE"), refusedAt(json("[]")))
    }

    @Test
    fun everyProblemIsReportedNotOnlyTheFirst() {
        val document = JsonPatch.set(JsonPatch.set(exampleDocument, "/id", JsonPrimitive("X")), "/version", JsonPrimitive("one"))
        assertEquals(listOf("/id BAD_PATTERN", "/version BAD_PATTERN"), refusedAt(document))
    }

    @Test
    fun aPropsSchemaOverFourKibIsRefused() {
        val long = JsonPrimitive("x".repeat(PluginContentRules.MAX_PROPS_SCHEMA_BYTES))
        val document = JsonPatch.set(exampleDocument, "/elements/widget/props/properties/label/description", long)
        assertEquals(listOf("/elements/widget/props PROPS_SCHEMA_TOO_LARGE"), refusedAt(document))
    }

    @Test
    fun anAgentToolNameOverSixtyFourCharactersIsRefusedAtTheAction() {
        val segment = "a".repeat(40)
        val document = JsonPatch.set(exampleDocument, "/id", JsonPrimitive("letta.$segment"))
        val action = "x".repeat(30)
        val withAction = JsonPatch.set(document, "/actions/$action", json("""{"description":"d","visibility":["agent"],"input":{"type":"object"}}"""))
        assertEquals(listOf("/actions/$action BAD_TOOL_NAME"), refusedAt(withAction))
        val viewOnly = JsonPatch.set(document, "/actions/$action", json("""{"visibility":["view"],"input":{"type":"object"}}"""))
        assertIs<PluginManifestResult.Parsed>(PluginManifestParser.parse(viewOnly))
    }

    @Test
    fun aViewOnlyActionNeedsNoDescription() {
        assertNull(PluginRegistryFixtures.example.actions.getValue("refresh").description)
    }

    @Test
    fun aWildcardOriginUnderTwoLabelsAndAnIpWithAPortAreAccepted() {
        val manifest = manifest("/net/connect" to json("""["https://*.example.test", "ws://127.0.0.1:8188", "wss://[::1]:9000"]"""))
        assertEquals(3, manifest.net.connect.size)
    }

    @Test
    fun theSchemaKeepsCapabilitiesAClosedEnumOfTheModel() {
        val names = PluginManifestSchema.schema["properties"].toString()
        PluginCapability.entries.forEach { assertTrue(names.contains("\"${it.wire}\""), it.wire) }
        assertEquals(PluginCapability.entries.map { it.wire }, PluginCapability.wireNames)
    }

    @Test
    fun aSettingDefaultOfTheWrongTypeIsRefusedAtTheDefault() {
        val document = JsonPatch.set(exampleDocument, "/settings/quality/default", JsonPrimitive("high"))
        assertEquals(listOf("/settings/quality/default WRONG_TYPE"), refusedAt(document))
    }

    @Test
    fun refusalsPrintTheirPointers() {
        val refused = PluginManifestParser.parse(JsonPatch.remove(exampleDocument, "/id"))
        assertTrue(refused.toString().contains("/id MISSING_FIELD"), refused.toString())
    }
}
