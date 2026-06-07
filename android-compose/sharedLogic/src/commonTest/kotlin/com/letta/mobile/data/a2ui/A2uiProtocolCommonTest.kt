package com.letta.mobile.data.a2ui

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class A2uiProtocolCommonTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun capabilityDeclarationSerializesThePhaseOneHandshakePayload() {
        val out = json.encodeToString(A2uiCapabilityDeclaration())

        assertContains(out, "\"a2ui_version\":\"0.9\"")
        assertContains(out, "\"supported_catalogs\"")
        assertContains(out, A2UI_BASIC_CATALOG_ID)
        assertContains(out, LETTA_TOOL_APPROVAL_CATALOG_ID)
        assertContains(out, LETTA_SCHEDULE_CATALOG_ID)
        assertContains(out, "\"supported_widgets\"")
        assertContains(out, A2UI_LIST_VIEW_WIDGET_ID)
        assertContains(out, "LinearProgress")
        assertContains(out, "CircularProgress")
        assertContains(out, LETTA_TOOL_APPROVAL_WIDGET_ID)
        assertContains(out, LETTA_SCHEDULE_CARD_WIDGET_ID)
        assertContains(out, LETTA_SCHEDULE_SELECTOR_WIDGET_ID)
        assertContains(out, "\"theme_hints\"")
    }

    @Test
    fun createSurfaceParsesCatalogAndTheme() {
        val parsed = decodeA2uiMessages(
            json,
            json.parseToJsonElement(
                """
                {"version":"v0.9","createSurface":{"surfaceId":"tool-approval","catalogId":"com.letta.mobile:tool-approval/v1","theme":{"density":"compact"},"sendDataModel":true}}
                """.trimIndent(),
            ),
        ).single()

        val createSurface = assertIs<A2uiMessage.CreateSurface>(parsed)
        assertEquals("tool-approval", createSurface.surfaceId)
        assertEquals(LETTA_TOOL_APPROVAL_CATALOG_ID, createSurface.createSurface.catalogId)
        assertEquals(true, createSurface.createSurface.sendDataModel)
        assertEquals("compact", createSurface.createSurface.theme!!.jsonObject["density"]!!.jsonPrimitive.content)
    }

    @Test
    fun updateComponentsPreservesRawFields() {
        val parsed = decodeA2uiMessages(
            json,
            json.parseToJsonElement(
                """
                {"version":"v0.9","updateComponents":{"surfaceId":"s1","components":[{"id":"root","component":"Column","children":["title","approve"]},{"id":"approve","component":"ToolApprovalCard","requestId":"req-1","scopes":["once","session"]}]}}
                """.trimIndent(),
            ),
        ).single()

        val update = assertIs<A2uiMessage.UpdateComponents>(parsed)
        assertEquals(listOf("root", "approve"), update.updateComponents.components.map { it.id })
        assertEquals(listOf("title", "approve"), update.updateComponents.components.first().children)
        val approval = update.updateComponents.components[1]
        assertEquals(LETTA_TOOL_APPROVAL_WIDGET_ID, approval.component)
        assertEquals("req-1", approval.raw["requestId"]!!.jsonPrimitive.content)
    }

    @Test
    fun componentPayloadsWithoutStableIdentityAreRejected() {
        assertFailsWith<SerializationException> {
            decodeA2uiMessages(
                json,
                json.parseToJsonElement(
                    """
                    {"version":"v0.9","updateComponents":{"surfaceId":"s1","components":[{"component":"Text","text":"Missing id"}]}}
                    """.trimIndent(),
                ),
            )
        }
    }

    @Test
    fun listViewItemTemplateMetadataRoundTrips() {
        val parsed = decodeA2uiMessages(
            json,
            json.parseToJsonElement(
                """
                {"version":"v0.9","updateComponents":{"surfaceId":"s1","root":"list","components":[
                  {"id":"list","component":"ListView","itemTemplate":"issue-row","items":{"path":"/issues"},"itemKey":"id"},
                  {"id":"issue-row","component":"Row","children":["title","state"]},
                  {"id":"title","component":"Text","text":{"path":"title"}},
                  {"id":"state","component":"Text","text":{"path":"state"}}
                ]}}
                """.trimIndent(),
            ),
        ).single()

        val update = assertIs<A2uiMessage.UpdateComponents>(parsed)
        val list = update.updateComponents.components.first { it.id == "list" }
        assertEquals(A2UI_LIST_VIEW_WIDGET_ID, list.component)
        assertEquals("issue-row", list.listTemplate!!.itemTemplateComponentId)
        assertEquals("/issues", list.listTemplate!!.itemsPath)
        assertEquals("id", list.listTemplate!!.itemKeyPath)

        val encoded = json.encodeToString(A2uiMessageSerializer, update)
        val roundTripped = assertIs<A2uiMessage.UpdateComponents>(
            decodeA2uiMessages(json, json.parseToJsonElement(encoded)).single(),
        )
        val roundTrippedList = roundTripped.updateComponents.components.first { it.id == "list" }
        assertEquals("issue-row", roundTrippedList.raw["itemTemplate"]!!.jsonPrimitive.content)
        assertEquals("/issues", roundTrippedList.raw["items"]!!.jsonObject["path"]!!.jsonPrimitive.content)
        assertEquals("id", roundTrippedList.raw["itemKey"]!!.jsonPrimitive.content)
    }

    @Test
    fun listViewParsesShorthandItemKeyAndTemplateAliases() {
        val parsed = decodeA2uiMessages(
            json,
            json.parseToJsonElement(
                """
                {"version":"v0.9","updateComponents":{"surfaceId":"s1","components":[
                  {"id":"list","component":"ListView","templateComponentId":"entry","items":"/entries"}
                ]}}
                """.trimIndent(),
            ),
        ).single()

        val update = assertIs<A2uiMessage.UpdateComponents>(parsed)
        val list = update.updateComponents.components.single()
        assertEquals("entry", list.listTemplate!!.itemTemplateComponentId)
        assertEquals("/entries", list.listTemplate!!.itemsPath)
        assertEquals("id", list.listTemplate!!.itemKeyPath)
    }

    @Test
    fun updateDataModelOmittedValueParsesAsDeleteMarker() {
        val parsed = decodeA2uiMessages(
            json,
            json.parseToJsonElement(
                """{"version":"v0.9","updateDataModel":{"surfaceId":"s1","path":"/approval"}}""",
            ),
        ).single()

        val update = assertIs<A2uiMessage.UpdateDataModel>(parsed)
        assertEquals("/approval", update.updateDataModel.path)
        assertEquals(null, update.updateDataModel.value)
    }

    @Test
    fun updateDataModelExplicitNullIsDistinctFromOmittedValue() {
        val parsed = decodeA2uiMessages(
            json,
            json.parseToJsonElement(
                """{"version":"v0.9","updateDataModel":{"surfaceId":"s1","path":"/approval","value":null}}""",
            ),
        ).single()

        val update = assertIs<A2uiMessage.UpdateDataModel>(parsed)
        assertSame(JsonNull, update.updateDataModel.value)
    }

    @Test
    fun batchedMessagesParseFromWrapperPayload() {
        val parsed = decodeA2uiMessages(
            json,
            json.parseToJsonElement(
                """
                {"messages":[
                  {"version":"v0.9","createSurface":{"surfaceId":"s1","catalogId":"basic"}},
                  {"version":"v0.9","deleteSurface":{"surfaceId":"s1"}}
                ]}
                """.trimIndent(),
            ),
        )

        assertEquals(listOf("createSurface", "deleteSurface"), parsed.map { it.messageType })
        assertTrue(parsed.map { it.surfaceId }.contains("s1"))
    }

    @Test
    fun unknownEnvelopesDoNotBreakForwardCompatibleParsing() {
        val parsed = decodeA2uiMessages(
            json,
            json.parseToJsonElement("""{"version":"v0.9","futureSurface":{"surfaceId":"s1"}}"""),
        ).single()

        val unknown = assertIs<A2uiMessage.Unknown>(parsed)
        assertEquals("futureSurface", unknown.messageType)
        assertEquals("s1", unknown.raw["futureSurface"]!!.jsonObject["surfaceId"]!!.jsonPrimitive.contentOrNull)
    }
}
