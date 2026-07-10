package com.letta.mobile.desktop.chat

import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * letta-mobile-cq2ju: desktop selects the Iroh transport for iroh:// backends and
 * the WebSocket transport otherwise, mirroring the Android repository branch. This
 * guards the scheme-detection the DesktopAppServerControllerGatewayFactory uses to
 * choose IrohAppServerTransportAdapter vs KtorAppServerWebSocketTransport.
 */
class DesktopIrohTransportSelectionTest {

    @Test
    fun `iroh urls are detected for the iroh transport branch`() {
        assertTrue(IrohChannelTransport.isIrohUrl("iroh://abc123@192.168.50.90:4501"))
        assertTrue(IrohChannelTransport.isIrohUrl("iroh://abc@host:4501,host2:4501"))
        assertTrue(IrohChannelTransport.isIrohUrl("https://iroh://abc@h:1"))
    }

    @Test
    fun `ws and http urls stay on the websocket branch`() {
        assertFalse(IrohChannelTransport.isIrohUrl("ws://127.0.0.1:4500"))
        assertFalse(IrohChannelTransport.isIrohUrl("wss://example.com/shim"))
        assertFalse(IrohChannelTransport.isIrohUrl("https://example.com"))
        assertFalse(IrohChannelTransport.isIrohUrl(null))
    }
}
