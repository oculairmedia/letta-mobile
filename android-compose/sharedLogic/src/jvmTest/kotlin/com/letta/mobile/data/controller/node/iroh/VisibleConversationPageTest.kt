package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VisibleConversationPageTest {
    private val full = JsonArray((0 until 100).map { i ->
        buildJsonObject {
            put("id", "c$i")
            if (i < 27) put("agent_id", JsonNull) else put("agent_id", if (i % 2 == 0) "a" else "b")
            put("updated_at", java.time.Instant.ofEpochSecond((1000 - i).toLong()).toString())
            put("hidden", i == 31)
            put("archived", i == 32)
        }
    })
    private val expected = (27 until 100).filter { it != 31 && it != 32 }.map { "c$it" }
    private fun page(context: AdminRpcRequestContext = AdminRpcRequestContext.Authenticated, after: String? = null, limit: String? = null, hidden: Set<String> = emptySet()) =
        visibleConversationPage(full, hidden, context, after?.let(::ConversationId), limit).map { it.jsonObject["id"]!!.jsonPrimitive.content }

    @Test fun handlerOptInFillsPageButLegacyUnfilteredStillPassesThrough() = runTest {
        val queries = mutableListOf<Int>()
        val native = object : AppServerClient by FakeNativeClient() {
            override suspend fun conversationList(command: AppServerCommand.ConversationList): AppServerInboundFrame.ConversationListResponse {
                val count = command.query?.get("limit")?.jsonPrimitive?.content?.toLongOrNull()?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 20
                queries += count
                return AppServerInboundFrame.ConversationListResponse(command.requestId, true, conversations = JsonArray(full.take(count)))
            }
        }
        val root = kotlin.io.path.createTempDirectory("visible-conversation-uncached").toFile()
        try {
            java.io.File(root, "agents").mkdirs()
            val router = AdminRpcRouter()
            ConversationAdminHandlers.register(router, NativeReadTiers(
                nativeClient = native,
                localBackendStore = LocalBackendAdminStore(root, lmstudioBaseUrl = "http://e/v1"),
            ))
            suspend fun request(visible: Boolean): List<String> {
                val response = router.dispatch(AdminRpcInvocation("r", "conversation.list", buildJsonObject {
                    put("limit", 30)
                    if (visible) put("visible_only", true)
                }))
                return Json.parseToJsonElement(response).jsonObject.getValue("result").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
            }
            assertEquals((0 until 30).map { "c$it" }, request(false))
            assertEquals(expected.take(30), request(true))
            assertEquals(listOf(30, Int.MAX_VALUE), queries)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun visibleModeRequiresAgentVisibilityButLegacyDoesNot() = runTest {
        val native = FakeNativeClient()
        val router = AdminRpcRouter()
        ConversationAdminHandlers.register(router, NativeReadTiers(nativeClient = native))
        val visible = Json.parseToJsonElement(router.dispatch(AdminRpcInvocation(
            "visible", "conversation.list", buildJsonObject { put("visible_only", true) },
        ))).jsonObject
        assertEquals(false, visible.getValue("success").jsonPrimitive.content.toBoolean())
        assertTrue(visible.getValue("error").jsonPrimitive.content.contains("visible_only requires local agent visibility"))
        assertEquals(emptyList(), native.calls)
        val legacy = Json.parseToJsonElement(router.dispatch(AdminRpcInvocation("legacy", "conversation.list", null))).jsonObject
        assertEquals(true, legacy.getValue("success").jsonPrimitive.content.toBoolean())
        assertEquals(listOf("conversation_list"), native.calls)
    }

    @Test fun handlerSharesHeldListAndAppliesAgentScopeBeforeLimit() = runTest {
        val root = kotlin.io.path.createTempDirectory("visible-conversation-cache").toFile()
        try {
            LocalBackendFixtureStore.create(root)
            java.io.File(root, "agents/b.json").writeText("""{"id":"b","hidden":true}""")
            java.io.File(root, "conversations").mkdirs()
            var calls = 0
            val native = object : AppServerClient by FakeNativeClient() {
                override suspend fun conversationList(command: AppServerCommand.ConversationList): AppServerInboundFrame.ConversationListResponse {
                    calls++
                    return AppServerInboundFrame.ConversationListResponse(command.requestId, true, conversations = full)
                }
            }
            val router = AdminRpcRouter()
            ConversationAdminHandlers.register(router, NativeReadTiers(
                nativeClient = native,
                localBackendStore = LocalBackendAdminStore(root, lmstudioBaseUrl = "http://e/v1"),
            ))
            router.dispatch(AdminRpcInvocation("agent", "conversation.list", buildJsonObject { put("agent_id", "a") }))
            val response = router.dispatch(AdminRpcInvocation(
                "recents", "conversation.list", buildJsonObject { put("visible_only", true); put("limit", 30) },
                AdminRpcRequestContext(true, authorizedAgentIds = setOf("a")),
            ))
            val ids = Json.parseToJsonElement(response).jsonObject.getValue("result").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
            assertEquals(expected.filter { it.removePrefix("c").toInt() % 2 == 0 }.take(30), ids)
            val unscoped = router.dispatch(AdminRpcInvocation(
                "unscoped-recents", "conversation.list", buildJsonObject { put("visible_only", true); put("limit", 30) },
            ))
            val visibleIds = Json.parseToJsonElement(unscoped).jsonObject.getValue("result").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
            assertEquals(expected.filter { it.removePrefix("c").toInt() % 2 == 0 }.take(30), visibleIds, "hidden agent b must be removed before the page is cut")
            assertEquals(1, calls, "agent pages and visible recents must reuse the same full fetch")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun fillsThirtyAfterFilteringRawWindow() { assertEquals(expected.take(30), page()) }
    @Test fun excludesHiddenAgentsConversationsAndArchives() {
        assertEquals(expected.filter { it.removePrefix("c").toInt() % 2 == 0 }, page(limit = "100", hidden = setOf("b")))
    }
    @Test fun scopesBeforeLimit() {
        assertEquals(expected.filter { it.removePrefix("c").toInt() % 2 == 0 }.take(30), page(AdminRpcRequestContext(true, authorizedAgentIds = setOf("a"))))
        assertEquals(listOf("c90", "c91"), page(AdminRpcRequestContext(true, authorizedConversationIds = setOf("c90", "c91"))))
    }
    @Test fun cursorHasNoGapsOrDuplicates() {
        val first = page(limit = "30")
        assertEquals(expected.take(60), first + page(after = first.last(), limit = "30"))
        assertEquals(expected.take(30), page(after = "missing"))
    }
    @Test fun sortingUsesRecencyFallbackAndActualInstants() {
        val rows = JsonArray(listOf(
            buildJsonObject { put("id", "created"); put("agent_id", "a"); put("created_at", "2026-10-29T20:00:00Z") },
            buildJsonObject { put("id", "message"); put("agent_id", "a"); put("last_message_at", "2026-10-30T01:00:00+02:00"); put("updated_at", "2026-10-31T00:00:00Z") },
            buildJsonObject { put("id", "updated"); put("agent_id", "a"); put("updated_at", "2026-10-30T00:00:00Z") },
        ))
        assertEquals(listOf("updated", "message", "created"), visibleConversationPage(rows, emptySet(), AdminRpcRequestContext.Authenticated, null, null).map { it.jsonObject["id"]!!.jsonPrimitive.content })
    }

    @Test fun extremeLimits() {
        listOf("-2147483648", "-2147483649", Long.MIN_VALUE.toString(), "0").forEach { assertEquals(emptyList(), page(limit = it)) }
        assertEquals(expected, page(limit = Long.MAX_VALUE.toString()))
        assertEquals(expected.dropLast(1), page(limit = "-1"))
        assertEquals(expected.take(30), page(limit = "9223372036854775808"))
    }
    @Test fun legacyAndAgentAndSearchRequestsNeverOptIn() {
        assertFalse(wantsVisibleConversationPage(null))
        assertFalse(wantsVisibleConversationPage(buildJsonObject { put("limit", 30) }))
        assertTrue(wantsVisibleConversationPage(buildJsonObject { put("visible_only", true) }))
        assertFalse(wantsVisibleConversationPage(buildJsonObject { put("visible_only", true); put("agent_id", "a") }))
        assertFalse(wantsVisibleConversationPage(buildJsonObject { put("visible_only", true); put("summary_search", "query") }))
    }
}
