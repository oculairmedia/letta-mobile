package com.letta.mobile.data.transport.iroh

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * letta-mobile-bn008.1: headless probe for the agent address book.
 * No networking — pure store/resolver logic + typed-unavailable contract.
 */
class IrohAgentAddressResolverTest {

    private val tmp = File.createTempFile("bn008-addr", ".kv").apply { deleteOnExit() }

    @AfterTest fun cleanup() { tmp.delete() }

    private fun resolver() = IrohAgentAddressResolver(FileIrohAgentAddressStore(tmp))

    @Test
    fun registeredAgentResolvesToItsAddress() {
        val r = resolver()
        val addr = IrohAgentAddress("agent-A", nodeIdHex = "aabbcc", directAddrs = listOf("1.2.3.4:5"))
        r.publish(addr)

        val res = r.resolve("agent-A")
        val found = assertIs<AddressResolution.Found>(res)
        assertEquals("aabbcc", found.address.nodeIdHex)
        assertEquals(listOf("1.2.3.4:5"), found.address.directAddrs)
    }

    @Test
    fun unregisteredAgentReturnsTypedUnavailable_neverThrows() {
        val res = resolver().resolve("agent-nope")
        val unavailable = assertIs<AddressResolution.Unavailable>(res)
        assertEquals("agent-nope", unavailable.agentId)
        assertEquals("not_registered", unavailable.reason)
    }

    @Test
    fun blankAgentIdIsTypedUnavailable_neverThrows() {
        val res = resolver().resolve("")
        assertIs<AddressResolution.Unavailable>(res)
    }

    @Test
    fun wireRoundTripPreservesNodeIdAndAddrs() {
        val addr = IrohAgentAddress("agent-A", "deadbeef", listOf("a:1", "b:2"))
        val back = IrohAgentAddress.fromWire("agent-A", addr.toWire())
        assertEquals(addr.nodeIdHex, back.nodeIdHex)
        assertEquals(addr.directAddrs, back.directAddrs)
    }

    @Test
    fun reRegisterOverwritesAndUnregisterRemoves() {
        val r = resolver()
        r.publish(IrohAgentAddress("agent-A", "1111", listOf("x:1")))
        r.publish(IrohAgentAddress("agent-A", "2222", emptyList()))
        val found = assertIs<AddressResolution.Found>(r.resolve("agent-A"))
        assertEquals("2222", found.address.nodeIdHex)

        FileIrohAgentAddressStore(tmp).unregister("agent-A")
        assertIs<AddressResolution.Unavailable>(r.resolve("agent-A"))
    }
}
