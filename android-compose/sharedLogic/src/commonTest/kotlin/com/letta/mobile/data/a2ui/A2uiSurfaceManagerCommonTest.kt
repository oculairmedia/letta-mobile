package com.letta.mobile.data.a2ui

import app.cash.turbine.test
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class A2uiSurfaceManagerCommonTest {
    private val json = A2uiProtocolJson.Default

    @Test
    fun createComponentDataModelAndDeleteMessagesApplyToSurfaceState() {
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
            ),
        )

        val surface = manager.surface("s1")!!
        assertEquals(A2UI_BASIC_CATALOG_ID, surface.catalogId)
        assertEquals("compact", surface.theme!!.jsonObject["density"]!!.jsonPrimitive.content)
        assertEquals("card", surface.rootComponentId)
        assertEquals(listOf("card", "body"), surface.components.keys.toList())
        assertEquals("Ready", A2uiBindingResolver.resolvePath(surface.dataModel, "/message")!!.jsonPrimitive.content)

        manager.applyMessage(
            A2uiMessage.DeleteSurface(
                deleteSurface = A2uiDeleteSurfacePayload(surfaceId = "s1"),
            ),
        )

        assertNull(manager.surface("s1"))
    }

    @Test
    fun createAndDeleteInSameBatchDropsSurface() {
        val manager = A2uiSurfaceManager()

        manager.applyMessages(
            decodeA2uiMessages(
                json,
                json.parseToJsonElement(
                    """
                    [
                      {"version":"v0.9","createSurface":{"surfaceId":"s1","catalogId":"basic"}},
                      {"version":"v0.9","updateComponents":{"surfaceId":"s1","root":"body","components":[
                        {"id":"body","component":"Text","text":{"literalString":"Transient"}}
                      ]}},
                      {"version":"v0.9","deleteSurface":{"surfaceId":"s1"}}
                    ]
                    """.trimIndent(),
                ),
            ),
        )

        assertNull(manager.surface("s1"))
        assertEquals(emptyMap(), manager.surfaces.value)
    }

    @Test
    fun deleteForMissingSurfaceIsIgnored() {
        val manager = A2uiSurfaceManager()
        manager.applyMessages(
            decodeA2uiMessages(
                json,
                json.parseToJsonElement(
                    """
                    [
                      {"version":"v0.9","createSurface":{"surfaceId":"s1","catalogId":"basic"}},
                      {"version":"v0.9","updateComponents":{"surfaceId":"s1","root":"body","components":[
                        {"id":"body","component":"Text","text":{"literalString":"Still here"}}
                      ]}}
                    ]
                    """.trimIndent(),
                ),
            ),
        )

        manager.applyMessage(
            A2uiMessage.DeleteSurface(
                deleteSurface = A2uiDeleteSurfacePayload(surfaceId = "missing"),
            ),
        )

        assertEquals("body", manager.surface("s1")!!.rootComponentId)
        assertNull(manager.surface("missing"))
    }

    @Test
    fun liveSurfaceCanBeDismissedLocally() {
        val manager = A2uiSurfaceManager()
        manager.applyMessages(
            decodeA2uiMessages(
                json,
                json.parseToJsonElement(
                    """
                    [
                      {"version":"v0.9","createSurface":{"surfaceId":"s1","catalogId":"basic"}},
                      {"version":"v0.9","updateComponents":{"surfaceId":"s1","root":"body","components":[
                        {"id":"body","component":"Text","text":{"literalString":"Dismiss me"}}
                      ]}}
                    ]
                    """.trimIndent(),
                ),
            ),
        )

        manager.dismissSurface("s1")

        assertNull(manager.surface("s1"))
        assertEquals(emptyMap(), manager.surfaces.value)
    }

    @Test
    fun partialComponentUpdatesMergeWithoutLosingPreviousNodes() {
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
            ),
        )

        val surface = manager.surface("s1")!!
        assertEquals("root", surface.rootComponentId)
        assertTrue("root" in surface.components)
        assertTrue("title" in surface.components)
        assertEquals(listOf("title", "cta"), surface.components["root"]!!.children)
    }

    @Test
    fun surfaceSnapshotsCanBeReplacedAndCleared() {
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
            ),
        )
        manager.replaceWith(messages)

        assertNull(manager.surface("stale"))
        assertNull(manager.surface("s1"))
        assertEquals(A2UI_BASIC_CATALOG_ID, manager.surface("s2")!!.catalogId)

        manager.clear()

        assertEquals(emptyMap(), manager.surfaces.value)
    }

    @Test
    fun omittedDataModelValuesAreDistinctFromExplicitJsonNull() {
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
            ),
        )

        val dataModel = manager.surface("s1")!!.dataModel
        assertNull(A2uiBindingResolver.resolvePath(dataModel, "/approval/status"))
        assertSame(JsonNull, A2uiBindingResolver.resolvePath(dataModel, "/approval/reason"))
    }

    @Test
    fun dataModelOnlyChangesEmitSurfaceState() = runTest {
        val manager = A2uiSurfaceManager()
        manager.applyMessage(
            A2uiMessage.CreateSurface(
                createSurface = A2uiCreateSurfacePayload(
                    surfaceId = "s1",
                    catalogId = A2UI_BASIC_CATALOG_ID,
                ),
            ),
        )

        manager.surfaces.test {
            assertEquals(0L, awaitItem()["s1"]!!.dataModelRevision)

            manager.applyMessage(
                A2uiMessage.UpdateDataModel(
                    updateDataModel = A2uiUpdateDataModelPayload(
                        surfaceId = "s1",
                        path = "/message",
                        value = JsonPrimitive("Ready"),
                    ),
                ),
            )

            val updated = awaitItem()["s1"]!!
            assertEquals(1L, updated.dataModelRevision)
            assertEquals("Ready", updated.dataModel.resolve("/message")!!.jsonPrimitive.content)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun envelopeRoutingMetadataAttachesToTouchedSurfaces() {
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
            ),
        )

        val surface = manager.surface("s1")!!
        assertEquals("agent-1", surface.agentId)
        assertEquals("conv-1", surface.conversationId)
        assertEquals("turn-1", surface.turnId)
        assertEquals("run-1", surface.runId)
        assertEquals("approval-1", surface.approvalRequestId)
    }

    @Test
    fun bindingResolverResolvesJsonPointerPathsAndEscapedPathSegments() {
        val dataModel = json.parseToJsonElement(
            """
            {
              "user": {"name": "Ada"},
              "items": [{"label": "First"}],
              "a/b": {"~key": true}
            }
            """.trimIndent(),
        )

        assertEquals("Ada", A2uiBindingResolver.resolvePath(dataModel, "/user/name")!!.jsonPrimitive.content)
        assertEquals("First", A2uiBindingResolver.resolvePath(dataModel, "/items/0/label")!!.jsonPrimitive.content)
        assertEquals("true", A2uiBindingResolver.resolvePath(dataModel, "/a~1b/~0key")!!.jsonPrimitive.content)
    }

    @Test
    fun bindingResolverResolvesLiteralAndPathBindingsToDisplayText() {
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

        assertEquals("3", A2uiBindingResolver.displayText((path as A2uiResolvedBinding.Value).value))
        assertEquals("Approve", A2uiBindingResolver.displayText((literal as A2uiResolvedBinding.Value).value))
    }

    @Test
    fun bindingResolverEvaluatesCatalogFunctionCalls() {
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

        assertEquals(
            "Hello Emmanuel: 5/10",
            resolved("""{"call":"formatString","args":{"template":"Hello ${'$'}{/userName}: ${'$'}{/done}/${'$'}{/total}"}}"""),
        )
        assertEquals(
            "1,234.5",
            resolved("""{"call":"formatNumber","args":{"value":{"path":"/price"},"maximumFractionDigits":1}}"""),
        )
        assertEquals(
            "${'$'}1,234.50",
            resolved("""{"call":"formatCurrency","args":{"value":{"path":"/price"},"currency":"USD"}}"""),
        )
        assertEquals(
            "2026-05-28",
            resolved("""{"call":"formatDate","args":{"value":{"path":"/createdAt"},"pattern":"yyyy-MM-dd"}}"""),
        )
        assertEquals(
            "5 tasks",
            resolved("""{"call":"pluralize","args":{"count":{"path":"/done"},"singular":"task","plural":"tasks"}}"""),
        )
        assertEquals("true", resolved("""{"call":"email","args":{"value":{"path":"/email"}}}"""))
        assertEquals(
            "true",
            resolved("""{"call":"and","args":{"values":[{"call":"numeric","args":{"value":{"path":"/total"}}},{"path":"/enabled"}]}}"""),
        )
        assertEquals("<unknown function: missingFunction>", resolved("""{"call":"missingFunction","args":{}}"""))
    }

    @Test
    fun actionContextObjectAndPairsResolveBindingsAgainstSurfaceDataModel() {
        val surface = A2uiSurfaceState(
            surfaceId = "s1",
            dataModel = A2uiDataModel(
                buildJsonObject {
                    put("approval", buildJsonObject { put("id", "approval-1") })
                    put("reason", "ship it")
                },
            ),
        )

        val objectContext = resolveA2uiActionContext(
            context = buildJsonObject {
                put("approvalId", buildJsonObject { put("path", "/approval/id") })
                put("note", buildJsonObject { put("literalString", "accepted") })
            },
            surface = surface,
        )
        val pairContext = resolveA2uiActionContext(
            context = json.parseToJsonElement(
                """
                [
                  {"key":"approvalId","path":"/approval/id"},
                  {"key":"reason","value":{"path":"/reason"}}
                ]
                """.trimIndent(),
            ),
            surface = surface,
        )

        assertEquals("approval-1", objectContext["approvalId"]!!.jsonPrimitive.content)
        assertEquals("accepted", objectContext["note"]!!.jsonPrimitive.content)
        assertEquals("approval-1", pairContext["approvalId"]!!.jsonPrimitive.content)
        assertEquals("ship it", pairContext["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun actionContextResolvesComponentValueReferencesThroughBoundDataModelPath() {
        val component = A2uiComponent(
            id = "note-field",
            component = "TextField",
            raw = JsonObject(
                mapOf(
                    "id" to JsonPrimitive("note-field"),
                    "component" to JsonPrimitive("TextField"),
                    "value" to buildJsonObject { put("path", "/draft/note") },
                ),
            ),
        )
        val surface = A2uiSurfaceState(
            surfaceId = "s1",
            components = mapOf(component.id to component),
            dataModel = A2uiDataModel(
                buildJsonObject {
                    put("draft", buildJsonObject { put("note", "approved") })
                },
            ),
        )

        val resolved = resolveA2uiActionContext(
            context = buildJsonObject {
                put("note", JsonPrimitive("$" + "note-field.value"))
                put("missing", JsonPrimitive("$" + "missing.value"))
            },
            surface = surface,
        )

        assertEquals("approved", resolved["note"]!!.jsonPrimitive.content)
        assertSame(JsonNull, resolved["missing"])
    }
}
