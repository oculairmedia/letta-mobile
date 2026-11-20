package com.letta.mobile.data.controller.node.iroh.servicecredentials

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServiceAddressTest {
    @Test
    fun plainHttpIsAllowedOnlyToLoopbackIpLiterals() {
        listOf("http://127.0.0.1:9000", "http://127.255.255.254", "http://[::1]:9000").forEach { url ->
            assertTrue(ServiceAddress.protectsSecret(url), url)
        }
    }

    @Test
    fun noPlainHttpHostThatIsNotAStrictLoopbackLiteralMayReceiveASecret() {
        listOf(
            // Numeric names the JDK's resolver would turn into 127.0.0.1 or look up.
            "http://2130706433:9000",
            "http://9999999999:9000",
            "http://017700000001:9000",
            "http://0x7f000001:9000",
            // Other loopback spellings, names and non-loopback literals.
            "http://127.1:9000",
            "http://localhost:9000",
            "http://[0:0:0:0:0:0:0:1]:9000",
            "http://[::ffff:127.0.0.1]:9000",
            "http://128.0.0.1:9000",
            "http://10.0.0.5:9000",
            "http://lan-host.example.invalid",
        ).forEach { url -> assertFalse(ServiceAddress.protectsSecret(url), url) }
    }

    @Test
    fun httpsIsAlwaysAllowedAndTheOriginIsLowercasedWithItsPort() {
        assertTrue(ServiceAddress.protectsSecret("https://api.example.invalid"))
        assertEquals("https://api.example.invalid:443", ServiceAddress.originOf("https://API.example.invalid/v1"))
        assertEquals("http://127.0.0.1:80", ServiceAddress.originOf("http://127.0.0.1"))
    }
}
