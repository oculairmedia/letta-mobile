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

            out shouldContain "\"a2ui_version\":\"v0.9\""
            out shouldContain "\"supported_catalogs\""
            out shouldContain A2UI_BASIC_CATALOG_ID
            out shouldContain LETTA_TOOL_APPROVAL_CATALOG_ID
            out shouldContain "\"supported_widgets\""
            out shouldContain LETTA_TOOL_APPROVAL_WIDGET_ID
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
                      {"version":"v0.9","createSurface":{"surfaceId":"s1","catalogId":"https://a2ui.org/specification/v0_9/basic_catalog.json"}},
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
