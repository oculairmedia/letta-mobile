package com.letta.mobile.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BackendUrlTelemetryTest {
    @Test
    fun preservesSchemeAndRedactsEndpointDetails() {
        val cases = listOf(
            "iroh://node-secret.example/path?token=iroh-token" to "iroh://",
            "https://user:password@api-secret.example/v1/chat?token=https-token" to "https://",
            "http://plain-secret.example:8080/private?token=http-token" to "http://",
        )

        for ((url, expectedPrefix) in cases) {
            val descriptor = backendUrlTelemetryDescriptor(url)
            assertTrue(descriptor.startsWith(expectedPrefix))
            assertEquals(expectedPrefix.length + 6, descriptor.length)
            listOf("node-secret", "api-secret", "plain-secret", "user", "password", "private", "token").forEach {
                assertFalse(descriptor.contains(it, ignoreCase = true))
            }
        }
    }

    @Test
    fun handlesAbsentAndMalformedValuesWithoutEchoingThem() {
        assertEquals("unknown", backendUrlTelemetryDescriptor(null))
        assertEquals("unknown", backendUrlTelemetryDescriptor(""))
        assertEquals("unknown", backendUrlTelemetryDescriptor("   "))
        assertEquals("unknown", backendUrlTelemetryDescriptor("host-secret/path?token=secret"))
    }

    @Test
    fun descriptorsAreStableAndDistinguishAuthorities() {
        val first = "https://first-private.example/path?token=one"
        val second = "https://second-private.example/path?token=two"
        assertEquals(backendUrlTelemetryDescriptor(first), backendUrlTelemetryDescriptor(first))
        assertNotEquals(backendUrlTelemetryDescriptor(first), backendUrlTelemetryDescriptor(second))
        assertEquals(
            backendUrlTelemetryDescriptor("https://first-private.example/other?token=changed"),
            backendUrlTelemetryDescriptor(first),
        )
    }
}
