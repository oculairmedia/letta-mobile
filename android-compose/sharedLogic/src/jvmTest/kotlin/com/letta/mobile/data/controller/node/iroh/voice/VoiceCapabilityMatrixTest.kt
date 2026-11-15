package com.letta.mobile.data.controller.node.iroh.voice

import com.letta.mobile.data.controller.node.iroh.AdminRpcRouter
import com.letta.mobile.data.controller.node.iroh.IrohPeerCapabilities
import com.letta.mobile.data.voice.host.HostVoiceWire
import io.ktor.client.engine.mock.MockEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The capability each voice method needs, and what the routes register and advertise. */
class VoiceCapabilityMatrixTest {
    @Test
    fun readsAreChatReadSpeechIsChatSendAndWritesAreAdmin() {
        val expected = mapOf(
            HostVoiceWire.SETUP_GET to IrohPeerCapabilities.CHAT_READ,
            HostVoiceWire.MODELS_LIST to IrohPeerCapabilities.CHAT_READ,
            HostVoiceWire.TRANSCRIBE to IrohPeerCapabilities.CHAT_SEND,
            HostVoiceWire.SPEECH to IrohPeerCapabilities.CHAT_SEND,
            HostVoiceWire.SETUP_SET to IrohPeerCapabilities.ADMIN_FULL,
            HostVoiceWire.SETUP_CLEAR to IrohPeerCapabilities.ADMIN_FULL,
            HostVoiceWire.SETUP_TEST to IrohPeerCapabilities.ADMIN_FULL,
        )
        assertEquals(expected, HostVoiceWire.METHODS.associateWith(IrohPeerCapabilities::forAdminMethod))
        val chatRole = IrohPeerCapabilities.CHAT_ROLE
        assertTrue(IrohPeerCapabilities.isAllowed(chatRole, IrohPeerCapabilities.forAdminMethod(HostVoiceWire.SPEECH)))
        assertFalse(IrohPeerCapabilities.isAllowed(chatRole, IrohPeerCapabilities.forAdminMethod(HostVoiceWire.SETUP_SET)))
    }

    @Test
    fun theRoutesRegisterEveryMethodAndAdvertiseTheContract() {
        val router = AdminRpcRouter()
        VoiceRoutes(
            VoiceSetupStore(InMemoryVoiceSetupPersistence(), InMemoryVoiceKeyStore()) { 0L },
            KtorVoiceSpeechUpstream(MockEngine { error("Unexpected speech server call") }),
            VoiceInFlight(),
        ) { 0L }.register(router)
        assertEquals(HostVoiceWire.METHODS, router.registeredMethods)
        assertEquals(setOf(HostVoiceWire.CAPABILITY), router.featureCapabilities)
        assertTrue(router.streamedHandler(HostVoiceWire.SPEECH) != null)
        assertEquals(setOf(HostVoiceWire.SPEECH), router.registeredMethods.filter { router.streamedHandler(it) != null }.toSet())
    }
}
