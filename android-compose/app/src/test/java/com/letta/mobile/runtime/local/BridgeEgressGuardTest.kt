package com.letta.mobile.runtime.local

import java.net.InetAddress
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edge-case coverage for the SSRF egress guard (audit P1.5).
 *
 * DNS is stubbed via the injectable resolver so the assertions are deterministic and
 * never touch the network. Literal IPs passed to [InetAddress.getByName] are parsed,
 * not resolved, so the address flags (loopback / link-local / …) are exact.
 */
class BridgeEgressGuardTest {

    private fun resolvesTo(vararg literals: String): (String) -> List<InetAddress> =
        { literals.map { InetAddress.getByName(it) } }

    @Test
    fun `link-local metadata endpoint is blocked`() {
        assertTrue(
            BridgeEgressGuard.isBlockedHost("metadata.example", resolvesTo("169.254.169.254")),
        )
    }

    @Test
    fun `dns resolution failure fails closed`() {
        val throwing: (String) -> List<InetAddress> = { throw UnknownHostException("no such host") }
        assertTrue(
            "A host whose DNS lookup throws must be treated as blocked (fail closed).",
            BridgeEgressGuard.isBlockedHost("unresolvable.invalid", throwing),
        )
    }

    @Test
    fun `empty dns result is blocked`() {
        assertTrue(BridgeEgressGuard.isBlockedHost("empty.example") { emptyList() })
    }

    @Test
    fun `trailing dot host is normalized before resolution`() {
        var seen: String? = null
        val capturing: (String) -> List<InetAddress> = { host ->
            seen = host
            listOf(InetAddress.getByName("93.184.216.34"))
        }
        val blocked = BridgeEgressGuard.isBlockedHost("foo.local.", capturing)
        assertEquals("foo.local", seen)
        assertFalse(blocked)
    }

    @Test
    fun `ipv6 loopback is blocked`() {
        assertTrue(BridgeEgressGuard.isBlockedHost("v6.example", resolvesTo("::1")))
    }

    @Test
    fun `ipv4 loopback is blocked`() {
        assertTrue(BridgeEgressGuard.isBlockedHost("lo.example", resolvesTo("127.0.0.1")))
    }

    @Test
    fun `literal localhost is blocked without resolving`() {
        // Resolver must never be consulted for the well-known name.
        assertTrue(
            BridgeEgressGuard.isBlockedHost("LOCALHOST") { error("resolver should not be called") },
        )
    }

    @Test
    fun `normal public host is allowed`() {
        assertFalse(
            BridgeEgressGuard.isBlockedHost("example.com", resolvesTo("93.184.216.34")),
        )
    }

    @Test
    fun `host with a blocked address among public ones is blocked`() {
        // Any single loopback/link-local answer is enough to reject the host.
        assertTrue(
            BridgeEgressGuard.isBlockedHost("mixed.example", resolvesTo("93.184.216.34", "127.0.0.1")),
        )
    }
}
