package com.letta.mobile.data.pairing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class PairLinkTest {
    private val envelope = "letta-qr-v1.eyJ2ZXJzaW9uIjoxfQ"
    private val ticket = "endpointabc123+/=?&x"

    @Test
    fun encodeThenParseRoundTripsEnvelopeAndDial() {
        val link = PairLink(envelope, ticket)
        val encoded = link.encode()
        assertEquals(true, encoded.startsWith("meridian://pair?qr="))
        assertEquals(link, PairLink.parse(encoded))
    }

    @Test
    fun acceptsTheLettaAliasABareEnvelopeAndAMissingDial() {
        assertEquals(PairLink(envelope, null), PairLink.parse("letta://pair?qr=$envelope"))
        assertEquals(PairLink(envelope, null), PairLink.parse("  $envelope "))
        assertEquals(PairLink(envelope, "n@h:1"), PairLink.parse("MERIDIAN://pair?dial=n%40h%3A1&qr=$envelope"))
    }

    @Test
    fun rejectsAnythingThatIsNotAPairingLink() {
        assertNull(PairLink.parse("https://example.com/?qr=$envelope"))
        assertNull(PairLink.parse("meridian://pair?qr=not-an-envelope"))
        assertNull(PairLink.parse("meridian://pair?qr=%ZZ"))
        assertNull(PairLink.parse("meridian://conversation/abc"))
        assertFalse(PairLink.isPairLink("letta://agent/abc"))
    }

    @Test
    fun toStringNeverContainsTheSecretEnvelope() {
        assertFalse(envelope in PairLink(envelope, ticket).toString())
    }

    @Test
    fun aLinkThatFitsIsDrawnWhole() {
        val link = PairLink(envelope, ticket)
        assertEquals(link.encode(), link.forQr())
    }

    /**
     * A host ticket as the dial hint is far longer than the envelope, and the two together overflow
     * even a version 40 symbol — which is what made the inviter render no code at all. The code keeps
     * the invite and drops the hint instead.
     */
    @Test
    fun aLinkPastTheSymbolCapacityKeepsTheInviteAndDropsTheDialHint() {
        val ticket = "endpoint" + "a".repeat(2_400)
        val link = PairLink(envelope, ticket)
        assertEquals(true, link.encode().length > PairLink.QR_MAX_CHARS)
        val drawn = link.forQr()
        assertEquals(PairLink(envelope, null).encode(), drawn)
        assertEquals(PairLink(envelope, null), drawn?.let(PairLink::parse))
    }

    @Test
    fun aBareInvitePastTheCapacityIsNotDrawnAtAll() {
        val huge = PairLink.QR_SCHEME_PREFIX + "x".repeat(PairLink.QR_MAX_CHARS)
        assertNull(PairLink(huge, null).forQr())
    }
}
