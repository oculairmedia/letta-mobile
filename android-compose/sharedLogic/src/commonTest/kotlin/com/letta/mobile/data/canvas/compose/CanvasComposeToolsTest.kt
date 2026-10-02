package com.letta.mobile.data.canvas.compose

import com.letta.mobile.data.canvas.CanvasToolContract
import com.letta.mobile.data.canvas.CanvasToolDefinition
import com.letta.mobile.data.controller.capability.Capability
import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.extras.ExternalToolResult
import com.letta.mobile.data.controller.extras.HostExternalTool
import com.letta.mobile.data.transport.appserver.AppServerExternalToolsGroup
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The canvas.compose pair as the model sees it (letta-mobile-bglj6.6): defined once in
 * [CanvasToolContract], short enough to sit in every tool list, and carried to the App Server
 * whole by the registry both hosts advertise through.
 */
class CanvasComposeToolsTest {
    /** A host tool that only has to be advertised: what the registry sends is the point here. */
    private class Advertised(definition: CanvasToolDefinition) : HostExternalTool {
        override val name = definition.name
        override val description = definition.description
        override val inputSchema: JsonObject = definition.inputSchema
        override val capability: Capability = Capability.ImageHydration

        override suspend fun invoke(input: JsonObject, agentId: String?): ExternalToolResult = ExternalToolResult.Success("")
    }

    @Test
    fun theContractDefinesComposeAndItsGuide() {
        assertEquals(listOf(CanvasToolContract.compose, CanvasToolContract.composeGuide), CanvasToolContract.composeTools)
        assertEquals(CanvasToolContract.COMPOSE, CanvasToolContract.compose.name)
        assertEquals(CanvasToolContract.COMPOSE_GUIDE, CanvasToolContract.composeGuide.name)
        assertEquals(CanvasComposeSchema.input, CanvasToolContract.compose.inputSchema)
        // The naming follows the other canvas tools, whichever scheme they use.
        assertEquals(CanvasToolContract.CREATE.substringBefore("create") + "compose", CanvasToolContract.COMPOSE)
    }

    @Test
    fun composeIsOfferedNowThatBothHostsAnswerIt() {
        // letta-mobile-bglj6.12: HostCanvasTools and CanvasExternalTools answer the pair, so it is
        // offered, and apply_ops sends an agent that wants notes or checklists to it.
        val offered = CanvasToolContract.all.map { it.name }
        CanvasToolContract.composeTools.forEach { assertTrue(it.name in offered, "${it.name} is not offered") }
        assertTrue(CanvasToolContract.COMPOSE in CanvasToolContract.applyOps.description)
    }

    @Test
    fun theComposeDescriptionStaysShortAndPointsAtTheGuide() {
        val description = CanvasToolContract.compose.description
        assertTrue(description.length < CanvasToolContract.COMPOSE_DESCRIPTION_MAX_CHARS, "${description.length} chars")
        ComposeKind.entries.forEach { assertTrue(it.name in description, "${it.name} missing") }
        assertTrue("No coordinates" in description)
        assertTrue(CanvasToolContract.COMPOSE_GUIDE in description)
        assertTrue("JSON-pointer" in description)
    }

    @Test
    fun theGuideTakesNoInput() {
        val schema = CanvasToolContract.composeGuide.inputSchema
        assertEquals(JsonPrimitive("object"), schema["type"])
        assertEquals(JsonObject(emptyMap()), schema.getValue("properties").jsonObject)
        assertEquals(JsonPrimitive(false), schema["additionalProperties"])
    }

    @Test
    fun bothDefinitionsSurviveTheAdvertisedToolGroups() {
        val registry = ExternalToolRegistry.hostTools(CanvasToolContract.composeTools.map(::Advertised))
        val groups = registry.advertisedToolsCommandGroups()!!
        // What goes on the wire, read back as the server would.
        val json = Json { encodeDefaults = false }
        val serializer = ListSerializer(AppServerExternalToolsGroup.serializer())
        val sent = json.decodeFromString(serializer, json.encodeToString(serializer, groups)).flatMap { it.tools }
        CanvasToolContract.composeTools.forEach { definition ->
            val tool = sent.single { it.name == definition.name }
            assertEquals(definition.description, tool.description)
            assertEquals(definition.inputSchema, tool.parameters)
        }
    }

    @Test
    fun theGuideCoversTheWholeContract() {
        val guide = CanvasComposeGuide.text
        (ComposeKind.entries.map { it.name } + ComposeErrorCode.entries.map { it.name } + ComposeProblemCode.entries.map { it.name })
            .forEach { assertTrue(it in guide, "the guide does not mention $it") }
        // Each cap where it is stated: CanvasComposeGuideTest.everyStatedCapIsTheConstant.
        CanvasComposeContract.COLOR_PRESETS.forEach { assertTrue(it in guide, "colour $it") }
        assertTrue(CanvasComposeContract.CATALOG in guide)
        assertTrue(CanvasComposeGuide.EXAMPLE_REQUEST in guide)
        requestOf(CanvasComposeContract.decode(CanvasComposeGuide.EXAMPLE_REQUEST))
    }
}
