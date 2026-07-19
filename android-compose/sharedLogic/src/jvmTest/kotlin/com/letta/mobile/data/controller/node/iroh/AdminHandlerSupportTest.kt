package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AdminHandlerSupportTest {
    @Test
    fun adminPathV1BuildsPrefixedUrl() {
        val url = AdminPath.v1("agents", "agent-1").build().url("http://admin.local")
        assertEquals("http://admin.local/v1/agents/agent-1", url)
    }

    @Test
    fun adminPathApiBuildsPrefixedUrl() {
        val url = AdminPath.api("projects", "proj-1", "beads-remote").build().url("http://admin.local")
        assertEquals("http://admin.local/api/projects/proj-1/beads-remote", url)
    }

    @Test
    fun adminPathChildAppendsSegments() {
        val url = AdminPath.v1("agents").child("agent-1", "context").build().url("http://admin.local")
        assertEquals("http://admin.local/v1/agents/agent-1/context", url)
    }

    @Test
    fun adminPathBuilderAppliesQueryParams() {
        val url = AdminPath.v1("agents").builder()
            .query("limit", "50")
            .build()
            .url("http://admin.local")
        assertEquals("http://admin.local/v1/agents?limit=50", url)
    }

    @Test
    fun requireParamReturnsValueWhenPresent() {
        val params = buildJsonObject { put("agent_id", "agent-1") }
        assertEquals("agent-1", params.requireParam(AdminParamKey("agent_id")))
    }

    @Test
    fun requireParamThrowsWhenMissing() {
        val error = assertFailsWith<IllegalArgumentException> {
            buildJsonObject {}.requireParam(AdminParamKey("agent_id"))
        }
        assertEquals("agent_id required", error.message)
    }

    @Test
    fun requireParamUsesCustomMessage() {
        val error = assertFailsWith<IllegalArgumentException> {
            null.requireParam(AdminParamKey("identifier"), AdminParamError(PROJECT_IDENTIFIER_REQUIRED))
        }
        assertEquals(PROJECT_IDENTIFIER_REQUIRED, error.message)
    }
}
