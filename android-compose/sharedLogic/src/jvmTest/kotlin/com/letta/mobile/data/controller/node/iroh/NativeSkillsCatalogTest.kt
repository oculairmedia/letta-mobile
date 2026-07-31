package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * lgns8.21.2 — the catalog must reflect an authoritative source, never fabricate one.
 *
 * Fixtures are the literal frames `@letta-ai/letta-code` 0.29.12 emits:
 * `emitSkillsUpdated` sends `{type,timestamp}`, and `buildDeviceStatus` hard-codes
 * `current_available_skills: []`.
 */
class NativeSkillsCatalogTest {

    private fun decode(raw: String): AppServerInboundFrame = AppServerProtocol.decodeFrame(raw).frame

    /**
     * Fail-on-revert: restoring the "any extracted array hydrates" behaviour makes
     * the hard-coded empty `current_available_skills` mark the catalog hydrated.
     */
    @Test
    fun hardCodedEmptyDeviceStatusSkillsDoesNotHydrate() {
        val catalog = NativeSkillsCatalog()
        catalog.ingest(
            decode(
                """
                {
                  "type": "update_device_status",
                  "runtime": {"agent_id": "agent-1", "conversation_id": "conv-1"},
                  "event_seq": 1,
                  "emitted_at": "2026-07-31T00:00:00Z",
                  "idempotency_key": "k1",
                  "device_status": {"current_available_skills": []}
                }
                """.trimIndent(),
            ),
        )
        assertFalse(catalog.isHydrated(), "hard-coded empty array is not an enumeration")
        assertEquals(SkillCatalogOrigin.None, catalog.origin())
    }

    /** Upstream `skills_updated` is `{type,timestamp}` — invalidation, never a snapshot. */
    @Test
    fun timestampOnlySkillsUpdatedDoesNotHydrate() {
        val catalog = NativeSkillsCatalog()
        val frame = decode("""{"type":"skills_updated","timestamp":1750000000000}""")
        assertTrue(frame is AppServerInboundFrame.SkillsUpdated, "must decode as typed frame, got $frame")
        assertEquals(1750000000000L, frame.timestamp)
        catalog.ingest(frame)
        assertFalse(catalog.isHydrated())
        assertFalse(catalog.isStale(), "nothing to invalidate before hydration")
    }

    /**
     * Catalog-reflects-authoritative-source: a populated `current_available_skills`
     * is a real enumeration and does hydrate, with its provenance recorded.
     */
    @Test
    fun populatedDeviceStatusSkillsHydratesWithProvenance() {
        val catalog = NativeSkillsCatalog()
        catalog.ingest(
            decode(
                """
                {
                  "type": "update_device_status",
                  "runtime": {"agent_id": "agent-1", "conversation_id": "conv-1"},
                  "event_seq": 2,
                  "emitted_at": "2026-07-31T00:00:00Z",
                  "idempotency_key": "k2",
                  "device_status": {"current_available_skills": ["alpha", {"name": "beta"}]}
                }
                """.trimIndent(),
            ),
        )
        assertTrue(catalog.isHydrated())
        assertEquals(SkillCatalogOrigin.DeviceStatus, catalog.origin())
        val listed = catalog.listEnvelope().toString()
        assertTrue(listed.contains("alpha") && listed.contains("beta"), listed)
        assertTrue(listed.contains("\"catalog_source\":\"device_status\""))
    }

    /** `skills_updated` after an enumeration marks it stale without discarding it. */
    @Test
    fun skillsUpdatedInvalidatesHydratedCatalog() {
        val catalog = NativeSkillsCatalog()
        catalog.hydrateFromHost(buildJsonArray { add(buildJsonObject { put("name", "demo") }) })
        catalog.ingest(decode("""{"type":"skills_updated","timestamp":1}"""))
        assertTrue(catalog.isHydrated(), "invalidation must not erase the last authoritative listing")
        assertTrue(catalog.isStale())
    }

    /**
     * `skill_enable_response` carries the only authoritative skill metadata upstream
     * puts on the wire (`name` / `skill_path` / `link_path`) and refreshes the catalog.
     */
    @Test
    fun enableAndDisableResponsesApplyAuthoritativeDeltas() {
        val catalog = NativeSkillsCatalog()
        catalog.hydrateFromHost(buildJsonArray { add(buildJsonObject { put("name", "demo") }) })
        catalog.ingest(
            decode(
                """
                {
                  "type": "skill_enable_response",
                  "request_id": "r1",
                  "success": true,
                  "name": "review",
                  "skill_path": "/skills/review",
                  "link_path": "/root/.letta/skills/review"
                }
                """.trimIndent(),
            ),
        )
        val afterEnable = catalog.listEnvelope().toString()
        assertTrue(afterEnable.contains("review"), afterEnable)
        assertTrue(afterEnable.contains("/root/.letta/skills/review"), afterEnable)
        assertFalse(catalog.isStale())

        catalog.ingest(
            decode("""{"type":"skill_disable_response","request_id":"r2","success":true,"name":"review"}"""),
        )
        val afterDisable = catalog.listEnvelope().toString()
        assertFalse(afterDisable.contains("review"), afterDisable)
        assertTrue(afterDisable.contains("demo"), afterDisable)
    }

    /** A lone mutation is not an enumeration; it must not hydrate an empty catalog. */
    @Test
    fun enableResponseAloneDoesNotHydrate() {
        val catalog = NativeSkillsCatalog()
        catalog.ingest(
            decode("""{"type":"skill_enable_response","request_id":"r1","success":true,"name":"review"}"""),
        )
        assertFalse(catalog.isHydrated())
        assertEquals(0, catalog.snapshot().size)
    }
}
