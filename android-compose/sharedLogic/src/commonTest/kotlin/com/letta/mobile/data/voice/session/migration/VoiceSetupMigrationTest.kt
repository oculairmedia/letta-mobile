package com.letta.mobile.data.voice.session.migration

import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import com.letta.mobile.data.voice.consent.EndpointOrigin
import com.letta.mobile.data.voice.host.HostVoiceErrorCode
import com.letta.mobile.data.voice.host.HostVoiceWire
import com.letta.mobile.data.voice.host.client.FakeHostVoiceLink
import com.letta.mobile.data.voice.host.client.HostVoiceReply
import com.letta.mobile.data.voice.host.client.SharedVoiceSetup
import com.letta.mobile.data.voice.host.client.VoiceFixtures
import com.letta.mobile.data.voice.host.client.reply
import com.letta.mobile.data.voice.session.VoiceSetupDraft
import com.letta.mobile.data.voice.session.VoiceSetupStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Sharing this device's own speech server with every device through the host, and what is offered when. */
class VoiceSetupMigrationTest {
    private val secrets = InMemorySecureSettingsStore()
    private val store = VoiceSetupStore(InMemorySecureSettingsStore(), secrets)
    private val nothing = VoiceFixtures.case(HostVoiceWire.SETUP_GET, "nothing stored")
    private val configured = VoiceFixtures.case(HostVoiceWire.SETUP_SET, "first setup with key and both grants")

    private fun link(admin: Boolean, hostStatus: JsonObject = nothing.response) = FakeHostVoiceLink().apply {
        answer = { method, _ ->
            when (method) {
                HostVoiceWire.SETUP_GET -> reply(hostStatus)
                HostVoiceWire.SETUP_TEST -> HostVoiceReply(false, error = if (admin) "voice.not_configured" else "forbidden")
                HostVoiceWire.SETUP_SET -> reply(configured.response)
                else -> error("unexpected $method")
            }
        }
    }

    private fun ownServer(key: String? = "sk-local") {
        assertNull(store.save(VoiceSetupDraft("https://speech.example.invalid", apiKey = key, consent = true)))
    }

    @Test
    fun anAdminDeviceWithItsOwnServerIsOfferedToShareIt() = runTest {
        ownServer()
        val shared = SharedVoiceSetup(link(admin = true)).also { it.learnAdmin() }
        assertEquals(VoiceShareOffer.SHARE, VoiceSetupMigration(store, shared).offer)
    }

    @Test
    fun anotherDeviceIsToldAnAdminDeviceCanShareIt() = runTest {
        ownServer()
        val shared = SharedVoiceSetup(link(admin = false)).also { it.learnAdmin() }
        assertEquals(false, shared.state.value.admin)
        assertEquals(VoiceShareOffer.ASK_ADMIN, VoiceSetupMigration(store, shared).offer)
    }

    @Test
    fun nothingIsOfferedWithoutTheCapabilityOrWithoutAServerOfItsOwn() = runTest {
        val shared = SharedVoiceSetup(link(admin = true)).also { it.learnAdmin() }
        assertEquals(VoiceShareOffer.NONE, VoiceSetupMigration(store, shared).offer)
        ownServer()
        val old = SharedVoiceSetup(link(admin = true).apply { capabilities = emptySet() }).also { it.learnAdmin() }
        assertEquals(VoiceShareOffer.NONE, VoiceSetupMigration(store, old).offer)
    }

    @Test
    fun onceTheHostSharesAServerRemovingThisDevicesOwnIsOffered() = runTest {
        ownServer()
        val shared = SharedVoiceSetup(link(admin = false, hostStatus = configured.response)).also { it.refresh() }
        val migration = VoiceSetupMigration(store, shared)
        assertEquals(VoiceShareOffer.REMOVE_LOCAL, migration.offer)

        migration.removeLocal()

        assertNull(store.setup.value.endpoint.baseUrl)
        assertFalse(store.setup.value.hasApiKey)
    }

    @Test
    fun sharingWithTheKeySendsItOnceAndRemovesItHereOnceTheHostHoldsIt() = runTest {
        ownServer()
        val link = link(admin = true)
        val shared = SharedVoiceSetup(link).also { it.learnAdmin() }

        assertNull(VoiceSetupMigration(store, shared).share(sendKey = true))

        val write = link.calls.single { it.first == HostVoiceWire.SETUP_SET }.second
        assertEquals("sk-local", write.getValue("api_key").jsonPrimitive.content)
        assertEquals("https://speech.example.invalid", write.getValue("base_url").jsonPrimitive.content)
        val grants = write.getValue("grants").jsonObject
        assertEquals("true", grants.getValue("audio_for_transcription").jsonPrimitive.content)
        assertEquals("true", grants.getValue("text_for_synthesis").jsonPrimitive.content)
        assertFalse(store.setup.value.hasApiKey, "the key leaves this device once the host holds it")
        assertEquals(EndpointOrigin("https://speech.example.invalid:443"), store.setup.value.hostAudioAck)
        assertTrue(shared.state.value.configured)
    }

    @Test
    fun sharingWithoutTheKeyKeepsItHereAndSendsNone() = runTest {
        ownServer()
        val link = link(admin = true)
        val shared = SharedVoiceSetup(link).also { it.learnAdmin() }

        assertNull(VoiceSetupMigration(store, shared).share(sendKey = false))

        val write = link.calls.single { it.first == HostVoiceWire.SETUP_SET }.second
        assertFalse("api_key" in write)
        assertTrue(store.setup.value.hasApiKey)
    }

    @Test
    fun aRefusedShareKeepsEverythingHereAndSaysWhy() = runTest {
        ownServer()
        val link = link(admin = true)
        val previous = link.answer
        link.answer = { method, params ->
            if (method == HostVoiceWire.SETUP_SET) HostVoiceReply(false, error = "forbidden") else previous(method, params)
        }
        val shared = SharedVoiceSetup(link).also { it.learnAdmin() }

        assertEquals(HostVoiceErrorCode.NOT_AUTHORIZED, VoiceSetupMigration(store, shared).share(sendKey = true))

        assertTrue(store.setup.value.hasApiKey)
        assertNull(store.setup.value.hostAudioAck)
    }
}
