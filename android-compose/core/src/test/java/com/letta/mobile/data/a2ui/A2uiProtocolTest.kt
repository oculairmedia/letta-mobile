package com.letta.mobile.data.a2ui

import io.kotest.core.spec.style.WordSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag

@Tag("unit")
class A2uiProtocolTest : WordSpec({
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    "A2UI capability declaration" should {
        "serialize the Phase 1 handshake payload" {
            val out = json.encodeToString(A2uiCapabilityDeclaration())

            out shouldContain "\"a2ui_version\":\"0.9\""
            out shouldContain "\"supported_catalogs\""
            out shouldContain A2UI_BASIC_CATALOG_ID
            out shouldContain LETTA_TOOL_APPROVAL_CATALOG_ID
            out shouldContain LETTA_SCHEDULE_CATALOG_ID
            out shouldContain "\"supported_widgets\""
            out shouldContain A2UI_LIST_VIEW_WIDGET_ID
            out shouldContain "LinearProgress"
            out shouldContain "CircularProgress"
            out shouldContain LETTA_TOOL_APPROVAL_WIDGET_ID
            out shouldContain LETTA_SCHEDULE_CARD_WIDGET_ID
            out shouldContain LETTA_SCHEDULE_SELECTOR_WIDGET_ID
            out shouldContain "\"theme_hints\""
        }
    }

    "A2UI message parsing" should {
        "parse createSurface with catalog and theme" {
            val parsed = decodeA2uiMessages(
                json,
                json.parseToJsonElement(
                    """
                    {"version":"v0.9","createSurface":{"surfaceId":"tool-approval","catalogId":"com.letta.mobile:tool-approval/v1","theme":{"density":"compact"},"sendDataModel":true}}
                    """.trimIndent(),
                ),
            ).single()

            parsed.shouldBeInstanceOf<A2uiMessage.CreateSurface>()
            parsed.surfaceId shouldBe "tool-approval"
            parsed.createSurface.catalogId shouldBe LETTA_TOOL_APPROVAL_CATALOG_ID
            parsed.createSurface.sendDataModel shouldBe true
            parsed.createSurface.theme!!.jsonObject["density"]!!.jsonPrimitive.content shouldBe "compact"
        }

        "parse updateComponents and preserve component raw fields" {
            val parsed = decodeA2uiMessages(
                json,
                json.parseToJsonElement(
                    """
                    {"version":"v0.9","updateComponents":{"surfaceId":"s1","components":[{"id":"root","component":"Column","children":["title","approve"]},{"id":"approve","component":"ToolApprovalCard","requestId":"req-1","scopes":["once","session"]}]}}
                    """.trimIndent(),
                ),
            ).single()

            parsed.shouldBeInstanceOf<A2uiMessage.UpdateComponents>()
            parsed.updateComponents.components.map { it.id } shouldBe listOf("root", "approve")
            parsed.updateComponents.components.first().children shouldBe listOf("title", "approve")
            val approval = parsed.updateComponents.components[1]
            approval.component shouldBe LETTA_TOOL_APPROVAL_WIDGET_ID
            approval.raw["requestId"]!!.jsonPrimitive.content shouldBe "req-1"
        }

        "round-trip ListView item template metadata" {
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

            parsed.shouldBeInstanceOf<A2uiMessage.UpdateComponents>()
            val list = parsed.updateComponents.components.first { it.id == "list" }
            list.component shouldBe A2UI_LIST_VIEW_WIDGET_ID
            list.listTemplate!!.itemTemplateComponentId shouldBe "issue-row"
            list.listTemplate!!.itemsPath shouldBe "/issues"
            list.listTemplate!!.itemKeyPath shouldBe "id"

            val encoded = json.encodeToString(A2uiMessageSerializer, parsed)
            val roundTripped = decodeA2uiMessages(json, json.parseToJsonElement(encoded)).single()
            roundTripped.shouldBeInstanceOf<A2uiMessage.UpdateComponents>()
            val roundTrippedList = roundTripped.updateComponents.components.first { it.id == "list" }
            roundTrippedList.raw["itemTemplate"]!!.jsonPrimitive.content shouldBe "issue-row"
            roundTrippedList.raw["items"]!!.jsonObject["path"]!!.jsonPrimitive.content shouldBe "/issues"
            roundTrippedList.raw["itemKey"]!!.jsonPrimitive.content shouldBe "id"
        }

        "parse ListView shorthand item key and template aliases" {
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

            parsed.shouldBeInstanceOf<A2uiMessage.UpdateComponents>()
            val list = parsed.updateComponents.components.single()
            list.listTemplate!!.itemTemplateComponentId shouldBe "entry"
            list.listTemplate!!.itemsPath shouldBe "/entries"
            list.listTemplate!!.itemKeyPath shouldBe "id"
        }

        "parse updateDataModel with omitted value as delete marker" {
            val parsed = decodeA2uiMessages(
                json,
                json.parseToJsonElement(
                    """{"version":"v0.9","updateDataModel":{"surfaceId":"s1","path":"/approval"}}""",
                ),
            ).single()

            parsed.shouldBeInstanceOf<A2uiMessage.UpdateDataModel>()
            parsed.updateDataModel.path shouldBe "/approval"
            parsed.updateDataModel.value shouldBe null
        }

        "parse updateDataModel explicit null distinctly from omitted value" {
            val parsed = decodeA2uiMessages(
                json,
                json.parseToJsonElement(
                    """{"version":"v0.9","updateDataModel":{"surfaceId":"s1","path":"/approval","value":null}}""",
                ),
            ).single()

            parsed.shouldBeInstanceOf<A2uiMessage.UpdateDataModel>()
            (parsed.updateDataModel.value === JsonNull) shouldBe true
        }

        "parse batched messages from wrapper payload" {
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

            parsed.map { it.messageType } shouldBe listOf("createSurface", "deleteSurface")
            parsed.map { it.surfaceId } shouldContain "s1"
        }

        "unknown envelopes do not break forward-compatible parsing" {
            val parsed = decodeA2uiMessages(
                json,
                json.parseToJsonElement("""{"version":"v0.9","futureSurface":{"surfaceId":"s1"}}"""),
            ).single()

            parsed.shouldBeInstanceOf<A2uiMessage.Unknown>()
            parsed.messageType shouldBe "futureSurface"
            parsed.raw["futureSurface"]!!.jsonObject["surfaceId"]!!.jsonPrimitive.contentOrNull shouldBe "s1"
        }
    }
})
