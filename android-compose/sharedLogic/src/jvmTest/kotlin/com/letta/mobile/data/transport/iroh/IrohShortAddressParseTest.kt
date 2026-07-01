package com.letta.mobile.data.transport.iroh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Hermetic guard for the human-friendly short dial form
 * `iroh://<node-id-hex>@<host:port>[,host:port...]` (letta-mobile-hgblw).
 *
 * Parsing is a pure companion function — NO live Iroh endpoint is bound and no
 * network is touched, so this runs in offline/restricted CI.
 */
class IrohShortAddressParseTest {
    private val nodeId = "79b5562e8fe654f94078b112e8a98ba7901f853ae695bed7e0e3910bad049664"

    @Test
    fun shortFormWithSingleDirectAddrParses() {
        val addr = IrohAppServerTransportAdapter.parseIrohAddress("$nodeId@192.168.50.90:4501")
        assertEquals(listOf("192.168.50.90:4501"), addr.directAddresses())
    }

    @Test
    fun shortFormWithMultipleDirectAddrsParses() {
        val addr = IrohAppServerTransportAdapter.parseIrohAddress("$nodeId@192.168.50.90:4501,100.93.254.12:4501")
        assertEquals(2, addr.directAddresses().size)
    }

    @Test
    fun bareHexNodeIdStillParses() {
        val addr = IrohAppServerTransportAdapter.parseIrohAddress(nodeId)
        assertTrue(addr.directAddresses().isEmpty())
    }

    @Test
    fun corruptedShortFormFailsLoudly() {
        assertFailsWith<IllegalArgumentException> {
            IrohAppServerTransportAdapter.parseIrohAddress("deadbeef@192.168.50.90:4501")
        }
        assertFailsWith<IllegalArgumentException> {
            IrohAppServerTransportAdapter.parseIrohAddress("$nodeId@")
        }
        assertFailsWith<IllegalArgumentException> {
            IrohAppServerTransportAdapter.parseIrohAddress("$nodeId@nocolon")
        }
    }

    @Test
    fun garbageTicketFailsLoudly() {
        assertFailsWith<IllegalArgumentException> {
            IrohAppServerTransportAdapter.parseIrohAddress("endpointnotaticket123")
        }
    }
}
