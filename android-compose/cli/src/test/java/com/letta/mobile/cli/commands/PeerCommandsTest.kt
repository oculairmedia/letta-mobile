package com.letta.mobile.cli.commands

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Pure-logic coverage for the `peer` CLI (d6e8g.7 slice 2): params mapping and
 * response formatting. The live Iroh dial is exercised end-to-end against a
 * running server, not in unit tests.
 */
class PeerCommandsTest {
    @Test
    fun inviteOmitsBlankNameAndNullTtl() {
        val minimal = PeerParams.invite(name = null, ttlMs = null)
        assertTrue(minimal.isEmpty(), "no name/ttl → empty params")

        val blank = PeerParams.invite(name = "  ", ttlMs = null)
        assertFalse(blank.containsKey("name"), "blank name is dropped")

        val full = PeerParams.invite(name = "desktop", ttlMs = 600000)
        assertEquals("desktop", full["name"]?.jsonPrimitive?.content)
        assertEquals(600000L, full["ttl_ms"]?.jsonPrimitive?.content?.toLong())
    }

    @Test
    fun nodeIdRenameAndCapabilitiesMapToParams() {
        val id = "a".repeat(64)
        assertEquals(id, PeerParams.nodeId(id)["node_id"]?.jsonPrimitive?.content)

        val renamed = PeerParams.rename(id, "laptop")
        assertEquals(id, renamed["node_id"]?.jsonPrimitive?.content)
        assertEquals("laptop", renamed["name"]?.jsonPrimitive?.content)

        val caps = PeerParams.setCapabilities(id, listOf("chat.read", "admin.full"))
        assertEquals(id, caps["node_id"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("chat.read", "admin.full"),
            caps["capabilities"]?.jsonArray?.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun formatterPrintsResultOnSuccessAndErrorOnFailure() {
        val ok = formatAdminResponse(true, buildJsonObject { put("revoked", true) }, null)
        assertTrue(ok.contains("revoked") && ok.contains("true"))

        val emptyOk = formatAdminResponse(true, null, null)
        assertEquals("{}", emptyOk)

        val err = formatAdminResponse(false, null, "capability_unavailable: nope")
        assertTrue(err.contains("\"success\":false"))
        assertTrue(err.contains("capability_unavailable: nope"))
    }

    @Test
    fun formatterEscapesArraysAndErrorText() {
        val arr = formatAdminResponse(true, JsonArray(listOf(JsonPrimitive("x"))), null)
        assertTrue(arr.trim().startsWith("["))
        // Error text with a quote must stay valid JSON (JsonPrimitive escaping).
        val err = formatAdminResponse(false, null, "he said \"hi\"")
        assertTrue(err.contains("\\\"hi\\\""), err)
    }
}
