package com.letta.mobile.desktop.chat

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import com.letta.mobile.desktop.shouldBindIrohTransport
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

    /**
     * letta-mobile-9v9nu regression: a real user config had `mode == LOCAL`
     * plus a leftover `iroh://` serverUrl from a prior remote session. Before
     * this fix, `rememberIrohTransport` keyed only on the URL and bound the
     * remote Iroh transport in Local mode, so the agent rail showed the
     * remote node's agents while the bundled local runtime never spawned.
     * Mode must win regardless of what the (possibly stale) URL says.
     */
    @Test
    fun `local mode never binds the iroh transport even with a stale iroh url`() {
        val staleLocalConfig = LettaConfig(
            id = "desktop-361c792e",
            mode = LettaConfig.Mode.LOCAL,
            serverUrl = "iroh://330415cc15c111596d0b18b730441be7717b92822b7517ccc09f92bb3946fa7f@192.168.50.90:4501",
        )

        assertFalse(shouldBindIrohTransport(staleLocalConfig))
    }

    @Test
    fun `local mode with blank server url never binds the iroh transport`() {
        val localConfig = LettaConfig(id = "local", mode = LettaConfig.Mode.LOCAL, serverUrl = "")

        assertFalse(shouldBindIrohTransport(localConfig))
    }

    @Test
    fun `non-local mode with an iroh url binds the iroh transport`() {
        val remoteConfig = LettaConfig(
            id = "remote",
            mode = LettaConfig.Mode.SELF_HOSTED,
            serverUrl = "iroh://abc123@192.168.50.90:4501",
        )

        assertTrue(shouldBindIrohTransport(remoteConfig))
    }

    @Test
    fun `non-local mode with an http url does not bind the iroh transport`() {
        val remoteConfig = LettaConfig(
            id = "remote",
            mode = LettaConfig.Mode.SELF_HOSTED,
            serverUrl = "http://localhost:8283",
        )

        assertFalse(shouldBindIrohTransport(remoteConfig))
    }
}
