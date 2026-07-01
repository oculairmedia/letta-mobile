package com.letta.mobile.data.transport.iroh

import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.RelayMode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Hermetic guard for the human-friendly short dial form
 * `iroh://<node-id-hex>@<host:port>[,host:port...]` (letta-mobile-hgblw).
 * No network dial — only address parsing.
 */
class IrohShortAddressParseTest {
    private val nodeId = "79b5562e8fe654f94078b112e8a98ba7901f853ae695bed7e0e3910bad049664"

    private fun adapter(): IrohAppServerTransportAdapter = runBlocking {
        val ep = Endpoint.bind(EndpointOptions(relayMode = RelayMode.Companion.defaultMode()))
        IrohAppServerTransportAdapter(ep)
    }

    @Test
    fun shortFormWithSingleDirectAddrParses() {
        val addr = adapter().parseIrohAddress("$nodeId@192.168.50.90:4501")
        assertEquals(listOf("192.168.50.90:4501"), addr.directAddresses())
    }

    @Test
    fun shortFormWithMultipleDirectAddrsParses() {
        val addr = adapter().parseIrohAddress("$nodeId@192.168.50.90:4501,100.93.254.12:4501")
        assertEquals(2, addr.directAddresses().size)
    }

    @Test
    fun bareHexNodeIdStillParses() {
        val addr = adapter().parseIrohAddress(nodeId)
        assertTrue(addr.directAddresses().isEmpty())
    }

    @Test
    fun corruptedShortFormFailsLoudly() {
        val a = adapter()
        assertFailsWith<IllegalArgumentException> { a.parseIrohAddress("deadbeef@192.168.50.90:4501") }
        assertFailsWith<IllegalArgumentException> { a.parseIrohAddress("$nodeId@") }
        assertFailsWith<IllegalArgumentException> { a.parseIrohAddress("$nodeId@nocolon") }
    }

    @Test
    fun garbageTicketFailsLoudly() {
        assertFailsWith<IllegalArgumentException> { adapter().parseIrohAddress("endpointnotaticket123") }
    }
}
