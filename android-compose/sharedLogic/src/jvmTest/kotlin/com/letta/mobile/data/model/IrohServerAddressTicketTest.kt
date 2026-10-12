package com.letta.mobile.data.model

import computer.iroh.EndpointAddr
import computer.iroh.EndpointId
import computer.iroh.EndpointTicket
import computer.iroh.SecretKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-ckmub.8: the commonMain ticket reader against tickets the real Iroh library writes
 * (what `appserver-serve-iroh` prints on its Ticket line), so a browser can dial the same address
 * desktop and Android accept.
 */
class IrohServerAddressTicketTest {
    @Test
    fun aRealEndpointTicketYieldsItsEndpointId() {
        // An endpoint id must be a valid ed25519 point, so take a real key's public half.
        val idBytes = SecretKey.generate().use { key -> key.public().toBytes() }
        val ticket = EndpointTicket.fromAddr(
            EndpointAddr(EndpointId.fromBytes(idBytes), null, listOf("192.168.50.90:4433")),
        ).toString()

        val address = IrohServerAddress.parse("iroh://$ticket")

        assertTrue(address.fromTicket)
        assertEquals(idBytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }, address.endpointIdHex)
    }
}
