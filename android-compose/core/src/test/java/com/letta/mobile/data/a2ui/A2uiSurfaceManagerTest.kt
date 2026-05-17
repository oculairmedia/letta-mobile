package com.letta.mobile.data.a2ui

import io.kotest.core.spec.style.WordSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Tag

@Tag("unit")
class A2uiSurfaceManagerTest : WordSpec({
    val json = A2uiProtocolJson.Default

    "A2UI surface manager" should {
        "apply create, component, data model, and delete messages" {
            val manager = A2uiSurfaceManager()

            manager.applyMessages(
                decodeA2uiMessages(
                    json,
                    json.parseToJsonElement(
                        """
                        [
                          {"version":"v0.9","createSurface":{"surfaceId":"s1","catalogId":"https://a2ui.org/specification/v0_9/basic_catalog.json","theme":{"density":"compact"}}},
                          {"version":"v0.9","updateComponents":{"surfaceId":"s1","root":"card","components":[
                            {"id":"card","component":"Card","child":"body"},
                            {"id":"body","component":"Text","text":{"path":"/message"}}
                          ]}},
                          {"version":"v0.9","updateDataModel":{"surfaceId":"s1","path":"/message","value":"Ready"}}
                        ]
                        """.trimIndent(),
                    ),
                )
            )

            val surface = manager.surface("s1")!!
            surface.catalogId shouldBe A2UI_BASIC_CATALOG_ID
            surface.theme!!.jsonObject["density"]!!.jsonPrimitive.content shouldBe "compact"
            surface.rootComponentId shouldBe "card"
            surface.components.keys.toList() shouldContainExactly listOf("card", "body")
            A2uiBindingResolver.resolvePath(surface.dataModel, "/message")!!.jsonPrimitive.content shouldBe "Ready"

            manager.applyMessage(
                A2uiMessage.DeleteSurface(
                    deleteSurface = A2uiDeleteSurfacePayload(surfaceId = "s1"),
                )
            )

            manager.surface("s1").shouldBeNull()
        }

        "merge partial component updates without losing previous nodes" {
            val manager = A2uiSurfaceManager()
            manager.applyMessages(
                decodeA2uiMessages(
                    json,
                    json.parseToJsonElement(
                        """
                        [
                          {"version":"v0.9","createSurface":{"surfaceId":"s1","catalogId":"basic"}},
                          {"version":"v0.9","updateComponents":{"surfaceId":"s1","root":"root","components":[{"id":"root","component":"Column","children":["title","cta"]}]}},
                          {"version":"v0.9","updateComponents":{"surfaceId":"s1","components":[{"id":"title","component":"Text","text":{"literalString":"Confirm"}}]}}
                        ]
                        """.trimIndent(),
                    ),
                )
            )

            val surface = manager.surface("s1")!!
            surface.rootComponentId shouldBe "root"
            surface.components shouldContainKey "root"
            surface.components shouldContainKey "title"
            surface.components["root"]!!.children shouldBe listOf("title", "cta")
        }

        "distinguish omitted data model values from explicit JSON null" {
            val manager = A2uiSurfaceManager()
            manager.applyMessages(
                decodeA2uiMessages(
                    json,
                    json.parseToJsonElement(
                        """
                        [
                          {"version":"v0.9","updateDataModel":{"surfaceId":"s1","path":"/approval/status","value":"pending"}},
                          {"version":"v0.9","updateDataModel":{"surfaceId":"s1","path":"/approval/reason","value":null}},
                          {"version":"v0.9","updateDataModel":{"surfaceId":"s1","path":"/approval/status"}}
                        ]
                        """.trimIndent(),
                    ),
                )
            )

            val dataModel = manager.surface("s1")!!.dataModel
            A2uiBindingResolver.resolvePath(dataModel, "/approval/status").shouldBeNull()
            A2uiBindingResolver.resolvePath(dataModel, "/approval/reason") shouldBe JsonNull
        }
    }

    "A2UI binding resolver" should {
        "resolve JSON pointer paths and escaped path segments" {
            val dataModel = json.parseToJsonElement(
                """
                {
                  "user": {"name": "Ada"},
                  "items": [{"label": "First"}],
                  "a/b": {"~key": true}
                }
                """.trimIndent(),
            )

            A2uiBindingResolver.resolvePath(dataModel, "/user/name")!!.jsonPrimitive.content shouldBe "Ada"
            A2uiBindingResolver.resolvePath(dataModel, "/items/0/label")!!.jsonPrimitive.content shouldBe "First"
            A2uiBindingResolver.resolvePath(dataModel, "/a~1b/~0key")!!.jsonPrimitive.content shouldBe "true"
        }

        "resolve literal and path bindings to display text" {
            val dataModel = buildJsonObject {
                put("count", 3)
            }

            val path = A2uiBindingResolver.resolve(
                binding = buildJsonObject { put("path", "/count") },
                dataModel = dataModel,
            )
            val literal = A2uiBindingResolver.resolve(
                binding = buildJsonObject { put("literalString", "Approve") },
                dataModel = dataModel,
            )

            A2uiBindingResolver.displayText((path as A2uiResolvedBinding.Value).value) shouldBe "3"
            A2uiBindingResolver.displayText((literal as A2uiResolvedBinding.Value).value) shouldBe "Approve"
        }
    }
})
