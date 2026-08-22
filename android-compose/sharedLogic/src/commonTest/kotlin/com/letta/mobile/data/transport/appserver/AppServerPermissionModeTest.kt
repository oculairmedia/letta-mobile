package com.letta.mobile.data.transport.appserver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AppServerPermissionModeTest {
    @Test
    fun currentWireValuesRoundTrip() {
        val expected = mapOf(
            "standard" to AppServerPermissionMode.Standard,
            "acceptEdits" to AppServerPermissionMode.AcceptEdits,
            "strict" to AppServerPermissionMode.Strict,
            "unrestricted" to AppServerPermissionMode.Unrestricted,
        )

        expected.forEach { (wireValue, mode) ->
            assertEquals(mode, AppServerPermissionMode.fromWireValue(wireValue))
            assertEquals("\"$wireValue\"", Json.encodeToString(mode))
        }
    }

    @Test
    fun legacyAndUnknownValuesAreRejected() {
        assertNull(AppServerPermissionMode.fromWireValue("memory"))
        assertNull(AppServerPermissionMode.fromWireValue("STRICT"))
        assertNull(AppServerPermissionMode.fromWireValue("future-mode"))
    }
}
