package com.letta.mobile.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class BackendUrlTelemetryTest {
    @Test
    fun endpointDetailsDoNotAffectDescriptor() {
        val urls = listOf(
            "https://first-user:first-password@first-secret.example:443/private?token=first-token",
            "https://second-user:second-password@second-secret.example:8443/other?token=second-token",
        )

        val descriptors = urls.map(::backendUrlTelemetryDescriptor)

        assertEquals(listOf("https", "https"), descriptors)
        listOf(
            "first-user",
            "first-password",
            "first-secret.example",
            "443",
            "private",
            "first-token",
            "second-user",
            "second-password",
            "second-secret.example",
            "8443",
            "other",
            "second-token",
        ).forEach { inputSubstring ->
            descriptors.forEach { descriptor ->
                assertFalse(descriptor.contains(inputSubstring, ignoreCase = true))
            }
        }
    }

    @Test
    fun supportedSchemesRemainDistinguishable() {
        val descriptors = listOf(
            backendUrlTelemetryDescriptor("iroh://node-secret.example/private?token=iroh-token"),
            backendUrlTelemetryDescriptor("https://api-secret.example/private?token=https-token"),
            backendUrlTelemetryDescriptor("http://plain-secret.example/private?token=http-token"),
        )

        assertEquals(listOf("iroh", "https", "http"), descriptors)
        assertEquals(descriptors.size, descriptors.toSet().size)
        assertNotEquals(descriptors[0], descriptors[1])
    }

    @Test
    fun absentMalformedAndUnsupportedValuesAreUnknown() {
        listOf(
            null,
            "",
            "   ",
            "host-secret/path?token=secret",
            "://host-secret",
            "ftp://host-secret/private",
        ).forEach { value ->
            assertEquals("unknown", backendUrlTelemetryDescriptor(value))
        }
    }
}
