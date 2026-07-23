package com.letta.mobile.appservercli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BackendOwnerInfoTest {
    @Test
    fun `round-trips through json`() {
        val info = BackendOwnerInfo(
            pid = 12345,
            startTimeMs = 1_700_000_000_000,
            backendDir = "/opt/backend/root",
            unit = "meridian-appserver.service",
            acquiredAtIso = "2026-07-22T23:00:00Z",
        )
        assertEquals(info, BackendOwnerInfo.fromJson(info.toJson()))
    }

    @Test
    fun `round-trips with null unit`() {
        val info = BackendOwnerInfo(1, 2, "/x", null, "2026-07-22T23:00:00Z")
        val parsed = BackendOwnerInfo.fromJson(info.toJson())
        assertEquals(info, parsed)
        assertNull(parsed!!.unit)
    }

    @Test
    fun `escapes special characters in paths`() {
        val info = BackendOwnerInfo(1, 2, "/weird/pa\"th\\with\ttabs", "u\"nit", "2026-07-22T23:00:00Z")
        assertEquals(info, BackendOwnerInfo.fromJson(info.toJson()))
    }

    @Test
    fun `malformed json parses to null`() {
        assertNull(BackendOwnerInfo.fromJson("not json"))
        assertNull(BackendOwnerInfo.fromJson("{}"))
        assertNull(BackendOwnerInfo.fromJson("{\"pid\":1}"))
    }
}
