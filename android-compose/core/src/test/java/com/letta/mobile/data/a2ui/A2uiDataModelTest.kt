package com.letta.mobile.data.a2ui

import io.kotest.core.spec.style.WordSpec
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Tag
import kotlin.system.measureNanoTime

@Tag("unit")
class A2uiDataModelTest : WordSpec({
    val json = A2uiProtocolJson.Default

    "A2UI JSON pointer" should {
        "resolve root, object keys, arrays, and escaped path segments" {
            val dataModel = json.parseToJsonElement(
                """
                {
                  "guest": {"name": "Ada"},
                  "rooms": [{"name": "Suite"}],
                  "a/b": {"~key": "escaped"}
                }
                """.trimIndent(),
            )

            A2uiJsonPointer.resolve(dataModel, "/") shouldBe dataModel
            A2uiJsonPointer.resolve(dataModel, "/guest/name")!!.jsonPrimitive.content shouldBe "Ada"
            A2uiJsonPointer.resolve(dataModel, "/rooms/0/name")!!.jsonPrimitive.content shouldBe "Suite"
            A2uiJsonPointer.resolve(dataModel, "/a~1b/~0key")!!.jsonPrimitive.content shouldBe "escaped"
            A2uiJsonPointer.resolve(dataModel, "/rooms/-").shouldBeNull()
        }

        "merge object patches without dropping existing fields" {
            val model = A2uiDataModel(
                buildJsonObject {
                    put("guest", buildJsonObject { put("first", "Ada") })
                }
            )

            model.applyPatch(
                "/guest",
                buildJsonObject {
                    put("last", "Lovelace")
                    put("meta", buildJsonObject { put("vip", true) })
                },
            )
            model.applyPatch(
                "/guest/meta",
                buildJsonObject { put("arrival", "18:00") },
            )

            val guest = model.resolve("/guest")!!.jsonObject
            guest["first"]!!.jsonPrimitive.content shouldBe "Ada"
            guest["last"]!!.jsonPrimitive.content shouldBe "Lovelace"
            guest["meta"]!!.jsonObject["vip"]!!.jsonPrimitive.content shouldBe "true"
            guest["meta"]!!.jsonObject["arrival"]!!.jsonPrimitive.content shouldBe "18:00"
        }

        "replace scalar values and append to arrays" {
            val model = A2uiDataModel(
                buildJsonObject {
                    put("guest", buildJsonObject { put("name", "Ada") })
                    put("rooms", JsonArray(emptyList()))
                }
            )

            model.applyPatch("/guest/name", JsonPrimitive("Grace"))
            model.applyPatch("/rooms/-", buildJsonObject { put("name", "Suite") })
            model.applyPatch("/rooms/-", buildJsonObject { put("name", "Studio") })

            model.resolve("/guest/name")!!.jsonPrimitive.content shouldBe "Grace"
            model.resolve("/rooms")!!.jsonArray.size shouldBe 2
            model.resolve("/rooms/1/name")!!.jsonPrimitive.content shouldBe "Studio"
        }

        "create intermediate arrays and objects when setting nested paths" {
            val model = A2uiDataModel()

            model.applyPatch("/rooms/0/name", JsonPrimitive("Suite"))
            model.applyPatch("/rooms/0/nights", JsonPrimitive(3))

            model.resolve("/rooms/0/name")!!.jsonPrimitive.content shouldBe "Suite"
            model.resolve("/rooms/0/nights")!!.jsonPrimitive.content shouldBe "3"
        }

        "delete omitted values while preserving explicit JSON null" {
            val model = A2uiDataModel(
                buildJsonObject {
                    put(
                        "approval",
                        buildJsonObject {
                            put("status", "pending")
                            put("reason", JsonNull)
                        },
                    )
                }
            )

            model.applyPatch("/approval/status", null)

            model.resolve("/approval/status").shouldBeNull()
            model.resolve("/approval/reason") shouldBe JsonNull
        }
    }

    "A2UI data model observations" should {
        "update only changed observed pointer states" {
            val model = A2uiDataModel(
                buildJsonObject {
                    put("title", "Pending")
                    put("body", "Unchanged")
                }
            )
            val title = model.observe("/title")
            val body = model.observe("/body")

            model.applyPatch("/title", JsonPrimitive("Confirmed"))

            title.value!!.jsonPrimitive.content shouldBe "Confirmed"
            body.value!!.jsonPrimitive.content shouldBe "Unchanged"
        }

        "apply streaming patches under the frame budget on average" {
            val manager = A2uiSurfaceManager()
            manager.applyMessage(
                A2uiMessage.CreateSurface(
                    createSurface = A2uiCreateSurfacePayload(
                        surfaceId = "perf",
                        catalogId = A2UI_BASIC_CATALOG_ID,
                    ),
                )
            )

            val totalNanos = measureNanoTime {
                repeat(1_000) { index ->
                    manager.applyMessage(
                        A2uiMessage.UpdateDataModel(
                            updateDataModel = A2uiUpdateDataModelPayload(
                                surfaceId = "perf",
                                path = "/counter",
                                value = JsonPrimitive(index),
                            ),
                        )
                    )
                }
            }

            val averageMillis = totalNanos / 1_000_000.0 / 1_000.0
            averageMillis shouldBeLessThan 16.0
            manager.surface("perf")!!.dataModel.resolve("/counter")!!.jsonPrimitive.content shouldBe "999"
        }
    }

    "A2UI surface manager streaming" should {
        "produce identical final state for batched and incremental valid streams" {
            val batched = A2uiSurfaceManager()
            val incremental = A2uiSurfaceManager()
            val messages = decodeA2uiMessages(
                json,
                json.parseToJsonElement(
                    """
                    [
                      {"version":"v0.9","createSurface":{"surfaceId":"booking","catalogId":"https://a2ui.org/specification/v0_9/basic_catalog.json"}},
                      {"version":"v0.9","updateComponents":{"surfaceId":"booking","root":"card","components":[
                        {"id":"card","component":"Card","child":"content"},
                        {"id":"content","component":"Column","children":["title","room"]},
                        {"id":"title","component":"Text","text":{"path":"/title"}},
                        {"id":"room","component":"Text","text":{"path":"/room/name"}}
                      ]}},
                      {"version":"v0.9","updateDataModel":{"surfaceId":"booking","path":"/title","value":"Booking confirmed"}},
                      {"version":"v0.9","updateDataModel":{"surfaceId":"booking","path":"/room","value":{"name":"Suite"}}}
                    ]
                    """.trimIndent(),
                ),
            )

            batched.applyMessages(messages)
            messages.forEach(incremental::applyMessage)

            val batchedSurface = batched.surface("booking")!!
            val incrementalSurface = incremental.surface("booking")!!
            incrementalSurface.rootComponentId shouldBe batchedSurface.rootComponentId
            incrementalSurface.components shouldBe batchedSurface.components
            incrementalSurface.dataModel.root shouldBe batchedSurface.dataModel.root
        }
    }
})
