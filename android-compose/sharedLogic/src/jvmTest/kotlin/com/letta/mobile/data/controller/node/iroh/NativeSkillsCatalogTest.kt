package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        assertFalse(listed.contains("\"controllable\":true"), "nothing advertised is controllable: $listed")
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
     * Root cause of the live toggle that could only fail: a populated 0.34 device status used to
     * REPLACE the host enumeration with entries that had no controllability, so the client
     * defaulted every skill to removable. It must now only add read-only entries.
     */
    @Test
    fun deviceStatusSkillsNeverReplaceTheHostEnumerationAndArriveReadOnly() {
        val catalog = NativeSkillsCatalog()
        catalog.hydrateFromHost(
            buildJsonArray {
                add(buildJsonObject { put("name", "linked"); put("source", "global"); put("controllable", true) })
                add(buildJsonObject { put("name", "agent-council"); put("source", "global"); put("controllable", false) })
            },
        )
        catalog.ingest(
            decode(
                """
                {
                  "type": "update_device_status",
                  "runtime": {"agent_id": "agent-1", "conversation_id": "conv-1"},
                  "event_seq": 3,
                  "emitted_at": "2026-10-16T00:00:00Z",
                  "idempotency_key": "k3",
                  "device_status": {"current_available_skills": [
                    {"id": "agent-council", "name": "agent-council", "description": "d", "path": "/x/SKILL.md", "source": "global"},
                    {"id": "letta-development-guide", "name": "Guide", "description": "d", "path": "/x/g/SKILL.md", "source": "global"},
                    {"id": "creating-skills", "name": "Creating skills", "description": "d", "path": "/b/SKILL.md", "source": "bundled"},
                    {"id": "my-notes", "name": "My notes", "description": "d", "path": "/m/SKILL.md", "source": "agent"}
                  ]}
                }
                """.trimIndent(),
            ),
        )
        assertEquals(SkillCatalogOrigin.HostEnumeration, catalog.origin())
        val byName = catalog.snapshot().associate {
            it.jsonObject.getValue("name").jsonPrimitive.content to it.jsonObject.getValue("controllable").jsonPrimitive.boolean
        }
        assertEquals(
            mapOf("linked" to true, "agent-council" to false, "creating-skills" to false, "my-notes" to false),
            byName,
            "host entries keep their flag; bundled/agent arrive read-only; global ones come from the host only",
        )
    }

    /** The filesystem is the truth: every listing re-reads the host root, and so does a mutation result. */
    @Test
    fun theListingAndMutationResultsReReadTheHostRoot() {
        var onDisk = listOf("alpha", "beta")
        val catalog = NativeSkillsCatalog(
            hostEnumeration = {
                buildJsonArray { onDisk.forEach { n -> add(buildJsonObject { put("name", n); put("controllable", true) }) } }
            },
        )
        val listing = catalog.asListingSource()
        assertEquals(listOf("alpha", "beta"), listing.currentSkills()?.map { it.jsonObject.getValue("name").jsonPrimitive.content })

        onDisk = listOf("beta")
        catalog.ingest(decode("""{"type":"skill_disable_response","request_id":"r2","success":true,"name":"alpha"}"""))
        assertEquals(listOf("beta"), catalog.snapshot().map { it.jsonObject.getValue("name").jsonPrimitive.content })
        assertFalse(catalog.isStale(), "a re-read is fresh, not stale")

        onDisk = listOf("beta", "gamma")
        assertEquals(listOf("beta", "gamma"), listing.currentSkills()?.map { it.jsonObject.getValue("name").jsonPrimitive.content })
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
