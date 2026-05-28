package com.letta.mobile.data.a2ui

import app.cash.turbine.test
import io.kotest.core.spec.style.WordSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
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
                          {"version":"v0.9","createSurface":{"surfaceId":"s1","catalogId":"basic","theme":{"density":"compact"}}},
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

        "replace and clear surface snapshots" {
            val manager = A2uiSurfaceManager()
            val messages = decodeA2uiMessages(
                json,
                json.parseToJsonElement(
                    """
                    [
                      {"version":"v0.9","createSurface":{"surfaceId":"s1","catalogId":"basic"}},
                      {"version":"v0.9","createSurface":{"surfaceId":"s2","catalogId":"basic"}},
                      {"version":"v0.9","deleteSurface":{"surfaceId":"s1"}}
                    ]
                    """.trimIndent(),
                ),
            )

            manager.applyMessages(
                decodeA2uiMessages(
                    json,
                    json.parseToJsonElement(
                        """
                        [{"version":"v0.9","createSurface":{"surfaceId":"stale","catalogId":"basic"}}]
                        """.trimIndent(),
                    ),
                )
            )
            manager.replaceWith(messages)

            manager.surface("stale").shouldBeNull()
            manager.surface("s1").shouldBeNull()
            manager.surface("s2")!!.catalogId shouldBe A2UI_BASIC_CATALOG_ID

            manager.clear()

            manager.surfaces.value shouldBe emptyMap()
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

        "emit surface state when only the data model changes" {
            runTest {
                val manager = A2uiSurfaceManager()
                manager.applyMessage(
                    A2uiMessage.CreateSurface(
                        createSurface = A2uiCreateSurfacePayload(
                            surfaceId = "s1",
                            catalogId = A2UI_BASIC_CATALOG_ID,
                        ),
                    )
                )

                manager.surfaces.test {
                    awaitItem()["s1"]!!.dataModelRevision shouldBe 0L

                    manager.applyMessage(
                        A2uiMessage.UpdateDataModel(
                            updateDataModel = A2uiUpdateDataModelPayload(
                                surfaceId = "s1",
                                path = "/message",
                                value = JsonPrimitive("Ready"),
                            ),
                        )
                    )

                    val updated = awaitItem()["s1"]!!
                    updated.dataModelRevision shouldBe 1L
                    updated.dataModel.resolve("/message")!!.jsonPrimitive.content shouldBe "Ready"
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        "attach envelope routing metadata to touched surfaces" {
            val manager = A2uiSurfaceManager()

            manager.apply(
                A2uiFrameEvent(
                    transport = "admin-shim",
                    frameId = "frame-1",
                    timestamp = "2026-05-17T12:00:00Z",
                    agentId = "agent-1",
                    conversationId = "conv-1",
                    turnId = "turn-1",
                    runId = "run-1",
                    requestId = "approval-1",
                    messages = decodeA2uiMessages(
                        json,
                        json.parseToJsonElement(
                            """
                            [
                              {"version":"v0.9","createSurface":{"surfaceId":"s1","catalogId":"basic"}},
                              {"version":"v0.9","updateComponents":{"surfaceId":"s1","root":"root","components":[{"id":"root","component":"Text","text":"Ready"}]}}
                            ]
                            """.trimIndent(),
                        ),
                    ),
                )
            )

            val surface = manager.surface("s1")!!
            surface.agentId shouldBe "agent-1"
            surface.conversationId shouldBe "conv-1"
            surface.turnId shouldBe "turn-1"
            surface.runId shouldBe "run-1"
            surface.approvalRequestId shouldBe "approval-1"
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

        "evaluate catalog function calls" {
            val dataModel = json.parseToJsonElement(
                """
                {
                  "userName": "Emmanuel",
                  "done": 5,
                  "total": 10,
                  "price": 1234.5,
                  "createdAt": "2026-05-28T12:00:00Z",
                  "email": "emmanuel@example.com",
                  "enabled": true
                }
                """.trimIndent(),
            )

            fun resolved(raw: String): String {
                val result = A2uiBindingResolver.resolve(json.parseToJsonElement(raw), dataModel)
                return A2uiBindingResolver.displayText((result as A2uiResolvedBinding.Value).value)
            }

            resolved("""{"call":"formatString","args":{"template":"Hello ${'$'}{/userName}: ${'$'}{/done}/${'$'}{/total}"}}""") shouldBe
                "Hello Emmanuel: 5/10"
            resolved("""{"call":"formatNumber","args":{"value":{"path":"/price"},"maximumFractionDigits":1}}""") shouldBe
                "1,234.5"
            resolved("""{"call":"formatCurrency","args":{"value":{"path":"/price"},"currency":"USD"}}""") shouldBe
                "${'$'}1,234.50"
            resolved("""{"call":"formatDate","args":{"value":{"path":"/createdAt"},"pattern":"yyyy-MM-dd"}}""") shouldBe
                "2026-05-28"
            resolved("""{"call":"pluralize","args":{"count":{"path":"/done"},"singular":"task","plural":"tasks"}}""") shouldBe
                "5 tasks"
            resolved("""{"call":"email","args":{"value":{"path":"/email"}}}""") shouldBe "true"
            resolved("""{"call":"and","args":{"values":[{"call":"numeric","args":{"value":{"path":"/total"}}},{"path":"/enabled"}]}}""") shouldBe
                "true"
            resolved("""{"call":"missingFunction","args":{}}""") shouldBe "<unknown function: missingFunction>"
        }
    }
})
