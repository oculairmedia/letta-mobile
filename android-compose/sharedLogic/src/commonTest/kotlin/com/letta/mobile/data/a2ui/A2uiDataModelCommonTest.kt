package com.letta.mobile.data.a2ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class A2uiDataModelCommonTest {
    private val json = A2uiProtocolJson.Default

    @Test
    fun jsonPointerResolvesRootObjectKeysArraysAndEscapedPathSegments() {
        val dataModel = json.parseToJsonElement(
            """
            {
              "guest": {"name": "Ada"},
              "rooms": [{"name": "Suite"}],
              "a/b": {"~key": "escaped"}
            }
            """.trimIndent(),
        )

        assertEquals(dataModel, A2uiJsonPointer.resolve(dataModel, "/"))
        assertEquals("Ada", A2uiJsonPointer.resolve(dataModel, "/guest/name")!!.jsonPrimitive.content)
        assertEquals("Suite", A2uiJsonPointer.resolve(dataModel, "/rooms/0/name")!!.jsonPrimitive.content)
        assertEquals("escaped", A2uiJsonPointer.resolve(dataModel, "/a~1b/~0key")!!.jsonPrimitive.content)
        assertNull(A2uiJsonPointer.resolve(dataModel, "/rooms/-"))
    }

    @Test
    fun jsonPointerPreservesEmptyAndWhitespaceSensitivePathTokens() {
        val dataModel = json.parseToJsonElement(
            """
            {
              "foo": {"": "empty child"},
              "title ": "space suffix"
            }
            """.trimIndent(),
        )

        assertEquals("empty child", A2uiJsonPointer.resolve(dataModel, "/foo/")!!.jsonPrimitive.content)
        assertEquals("space suffix", A2uiJsonPointer.resolve(dataModel, "/title ")!!.jsonPrimitive.content)
        assertNull(A2uiJsonPointer.resolve(dataModel, "/title"))
    }

    @Test
    fun objectPatchesMergeWithoutDroppingExistingFields() {
        val model = A2uiDataModel(
            buildJsonObject {
                put("guest", buildJsonObject { put("first", "Ada") })
            },
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
        assertEquals("Ada", guest["first"]!!.jsonPrimitive.content)
        assertEquals("Lovelace", guest["last"]!!.jsonPrimitive.content)
        assertEquals("true", guest["meta"]!!.jsonObject["vip"]!!.jsonPrimitive.content)
        assertEquals("18:00", guest["meta"]!!.jsonObject["arrival"]!!.jsonPrimitive.content)
    }

    @Test
    fun scalarValuesReplaceAndArraysAppend() {
        val model = A2uiDataModel(
            buildJsonObject {
                put("guest", buildJsonObject { put("name", "Ada") })
                put("rooms", JsonArray(emptyList()))
            },
        )

        model.applyPatch("/guest/name", JsonPrimitive("Grace"))
        model.applyPatch("/rooms/-", buildJsonObject { put("name", "Suite") })
        model.applyPatch("/rooms/-", buildJsonObject { put("name", "Studio") })

        assertEquals("Grace", model.resolve("/guest/name")!!.jsonPrimitive.content)
        assertEquals(2, model.resolve("/rooms")!!.jsonArray.size)
        assertEquals("Studio", model.resolve("/rooms/1/name")!!.jsonPrimitive.content)
    }

    @Test
    fun intermediateArraysAndObjectsAreCreatedWhenSettingNestedPaths() {
        val model = A2uiDataModel()

        model.applyPatch("/rooms/0/name", JsonPrimitive("Suite"))
        model.applyPatch("/rooms/0/nights", JsonPrimitive(3))

        assertEquals("Suite", model.resolve("/rooms/0/name")!!.jsonPrimitive.content)
        assertEquals("3", model.resolve("/rooms/0/nights")!!.jsonPrimitive.content)
    }

    @Test
    fun omittedValuesDeleteWhileExplicitJsonNullIsPreserved() {
        val model = A2uiDataModel(
            buildJsonObject {
                put(
                    "approval",
                    buildJsonObject {
                        put("status", "pending")
                        put("reason", JsonNull)
                    },
                )
            },
        )

        model.applyPatch("/approval/status", null)

        assertNull(model.resolve("/approval/status"))
        assertSame(JsonNull, model.resolve("/approval/reason"))
    }

    @Test
    fun onlyChangedObservedPointerStatesUpdate() {
        val model = A2uiDataModel(
            buildJsonObject {
                put("title", "Pending")
                put("body", "Unchanged")
            },
        )
        val title = model.observe("/title")
        val body = model.observe("/body")

        model.applyPatch("/title", JsonPrimitive("Confirmed"))

        assertEquals("Confirmed", title.value!!.jsonPrimitive.content)
        assertEquals("Unchanged", body.value!!.jsonPrimitive.content)
    }

    @Test
    fun incrementalValidStreamsProduceExpectedFinalState() {
        val manager = A2uiSurfaceManager()
        manager.applyMessage(
            A2uiMessage.CreateSurface(
                createSurface = A2uiCreateSurfacePayload(
                    surfaceId = "perf",
                    catalogId = A2UI_BASIC_CATALOG_ID,
                ),
            ),
        )

        repeat(1_000) { index ->
            manager.applyMessage(
                A2uiMessage.UpdateDataModel(
                    updateDataModel = A2uiUpdateDataModelPayload(
                        surfaceId = "perf",
                        path = "/counter",
                        value = JsonPrimitive(index),
                    ),
                ),
            )
        }

        assertEquals("999", manager.surface("perf")!!.dataModel.resolve("/counter")!!.jsonPrimitive.content)
    }

    @Test
    fun batchedAndIncrementalStreamsProduceIdenticalFinalState() {
        val batched = A2uiSurfaceManager()
        val incremental = A2uiSurfaceManager()
        val messages = decodeA2uiMessages(
            json,
            json.parseToJsonElement(
                """
                [
                  {"version":"v0.9","createSurface":{"surfaceId":"booking","catalogId":"basic"}},
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
        assertEquals(batchedSurface.rootComponentId, incrementalSurface.rootComponentId)
        assertEquals(batchedSurface.components, incrementalSurface.components)
        assertEquals(batchedSurface.dataModel.root, incrementalSurface.dataModel.root)
    }
}
