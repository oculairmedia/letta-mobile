package com.letta.mobile.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IrohServerAddressTest {
    private val hexId = "ab".repeat(32)

    @Test
    fun aHexEndpointIdParsesAndIsLowercased() {
        val address = IrohServerAddress.parse("iroh://${hexId.uppercase()}")
        assertEquals(hexId, address.endpointIdHex)
        assertEquals("iroh://$hexId", address.endpointUrl)
    }

    @Test
    fun theShortUrlKeepsItsDirectAddresses() {
        val address = IrohServerAddress.parse("iroh://$hexId@192.168.1.4:4433,10.0.0.2:4433")
        assertEquals(listOf("192.168.1.4:4433", "10.0.0.2:4433"), address.directAddresses)
    }

    @Test
    fun aBase32EndpointIdDecodesToTheSameBytes() {
        // 32 bytes of 0xAB in RFC 4648 base32 (no padding): 52 characters.
        val base32 = "VOV2XK5LVOV2XK5LVOV2XK5LVOV2XK5LVOV2XK5LVOV2XK5LVOVQ"
        assertEquals(hexId, IrohServerAddress.parse("iroh://${base32.lowercase()}").endpointIdHex)
    }

    @Test
    fun aTicketYieldsItsEndpointId() {
        // Variant 0, then the 32 id bytes, then address info: the postcard layout of an endpoint ticket.
        val ticket = "endpoint" + base32(byteArrayOf(0) + ByteArray(32) { 0xAB.toByte() } + byteArrayOf(1, 2, 3))
        val address = IrohServerAddress.parse("iroh://$ticket")
        assertEquals(hexId, address.endpointIdHex)
        assertTrue(address.fromTicket)
    }

    @Test
    fun aWrongLengthIdSaysWhatAnIdLooksLike() {
        val error = assertFailsWith<IllegalArgumentException> { IrohServerAddress.parse("iroh://abc123") }
        assertTrue("64 hex characters" in error.message.orEmpty(), error.message)
        assertTrue("iroh://<endpoint ticket>" in error.message.orEmpty(), error.message)
    }

    @Test
    fun aBrokenTicketIsReportedAsATicket() {
        val error = assertFailsWith<IllegalArgumentException> { IrohServerAddress.parse("iroh://endpoint" + "1".repeat(80)) }
        assertTrue("endpoint ticket" in error.message.orEmpty(), error.message)
    }

    @Test
    fun aDirectAddressWithoutAPortIsRejected() {
        assertNotNull(IrohServerAddress.problem("iroh://$hexId@myhost"))
        assertNull(IrohServerAddress.problem("iroh://$hexId@myhost:4433"))
    }

    @Test
    fun anOldHttpsWrappedConfigStillParses() {
        assertEquals(hexId, IrohServerAddress.parse("https://iroh://$hexId").endpointIdHex)
    }

    private fun base32(bytes: ByteArray): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out.append(alphabet[(buffer shr bits) and 31])
            }
            buffer = buffer and ((1 shl bits) - 1)
        }
        if (bits > 0) out.append(alphabet[(buffer shl (5 - bits)) and 31])
        return out.toString()
    }
}

class BackendServerUrlCheckTest {
    private val hexId = "0f".repeat(32)

    @Test
    fun selfHostedAcceptsIrohAndNetworkUrls() {
        assertNull(BackendServerUrlCheck.problem(LettaConfig.Mode.SELF_HOSTED, "iroh://$hexId"))
        assertNull(BackendServerUrlCheck.problem(LettaConfig.Mode.SELF_HOSTED, "https://letta.example.com"))
        assertNull(BackendServerUrlCheck.problem(LettaConfig.Mode.SELF_HOSTED, "ws://127.0.0.1:8283"))
    }

    @Test
    fun aBareEndpointIdIsToldToUseTheIrohScheme() {
        val problem = BackendServerUrlCheck.problem(LettaConfig.Mode.SELF_HOSTED, hexId)
        assertEquals("This looks like an Iroh endpoint id or ticket: enter it as iroh://$hexId", problem)
    }

    @Test
    fun aBlankOrSchemelessSelfHostedUrlIsAProblem() {
        assertNotNull(BackendServerUrlCheck.problem(LettaConfig.Mode.SELF_HOSTED, " "))
        assertNotNull(BackendServerUrlCheck.problem(LettaConfig.Mode.SELF_HOSTED, "letta.example.com"))
    }

    @Test
    fun theLocalRuntimeIsNeverChecked() {
        assertNull(BackendServerUrlCheck.problem(LettaConfig.Mode.LOCAL, "anything"))
    }
}
