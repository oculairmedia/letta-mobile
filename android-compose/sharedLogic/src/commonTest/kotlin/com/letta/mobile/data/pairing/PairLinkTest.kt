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
}
