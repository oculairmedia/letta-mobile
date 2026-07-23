package com.letta.mobile.data.controller.node.iroh

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * lgns8.9: project.* methods talk to VibeSync DIRECTLY (not lettashim), and
 * degrade to capability-unavailable when no VibeSync service is injected —
 * never a fall-back dial to :8291.
 */
class ProjectAdminHandlersTest {
    private var savedFactory: (() -> AdminProxyTransport)? = null
    private val dialedUrls = mutableListOf<String>()

    @BeforeTest
    fun recordDials() {
        savedFactory = AdminProxyClient.defaultTransportFactory
        AdminProxyClient.defaultTransportFactory = {
            AdminProxyTransport { _, url, _ ->
                dialedUrls += url
                AdminProxyTransportResponse(statusCode = 200, bodyText = "{\"ok\":true}")
            }
        }
    }

    @AfterTest
    fun restore() {
        savedFactory?.let { AdminProxyClient.defaultTransportFactory = it }
        dialedUrls.clear()
    }

    private fun router(vibesyncBaseUrl: String?): AdminRpcRouter {
        val r = AdminRpcRouter()
        ProjectAdminHandlers.register(r, vibesyncBaseUrl)
        return r
    }

    private suspend fun dispatch(r: AdminRpcRouter, method: String, params: Map<String, String> = emptyMap()): String =
        r.dispatch(
            AdminRpcInvocation(
                requestId = "t",
                method = method,
                params = buildJsonObject { params.forEach { (k, v) -> put(k, v) } },
                context = AdminRpcRequestContext.Authenticated,
            ),
        )

    @Test
    fun projectMethodsDialTheVibeSyncBaseDirectlyNotTheShim() = runTest {
        val r = router("http://127.0.0.1:3099")
        dispatch(r, "project.list", mapOf("limit" to "10"))
        dispatch(r, "project.get", mapOf("project_id" to "proj-1"))

        assertTrue(dialedUrls.isNotEmpty())
        dialedUrls.forEach { url ->
            assertTrue(url.startsWith("http://127.0.0.1:3099/api/"), "project method must dial VibeSync: $url")
            assertFalse(url.contains(":8291"), "project method must NOT dial lettashim: $url")
        }
    }

    @Test
    fun allNineProjectMethodsAreRegistered() {
        assertEquals(9, ProjectAdminHandlers.PROJECT_METHODS.size)
        val r = router("http://127.0.0.1:3099")
        assertTrue(ProjectAdminHandlers.PROJECT_METHODS.all { it in r.registeredMethods })
    }

    @Test
    fun withoutAVibeSyncServiceProjectsReturnCapabilityUnavailableWithoutDialing() = runTest {
        val r = router(vibesyncBaseUrl = null)
        ProjectAdminHandlers.PROJECT_METHODS.forEach { method ->
            val response = dispatch(r, method, mapOf("project_id" to "p"))
            assertTrue(response.contains("\"success\":false"), "$method must fail closed")
            assertTrue(response.contains("capability_unavailable"), "$method must be capability-unavailable")
        }
        assertTrue(dialedUrls.isEmpty(), "capability-unavailable must never dial any backend")
    }
}
