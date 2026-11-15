package com.letta.mobile.data.controller.node.iroh.voice

import com.letta.mobile.data.model.provider.wire.SecretWriteValue
import com.letta.mobile.data.voice.SpeechEndpointUrl
import com.letta.mobile.data.voice.SpeechModelName
import com.letta.mobile.data.voice.SpeechVoiceName
import com.letta.mobile.data.voice.host.HostVoiceEndpointChoice
import com.letta.mobile.data.voice.host.HostVoiceErrorCode
import com.letta.mobile.data.voice.host.HostVoiceGrants
import com.letta.mobile.data.voice.host.HostVoiceKeyChange
import com.letta.mobile.data.voice.host.HostVoiceSetupCommand
import com.letta.mobile.data.voice.host.HostVoiceSetupStatus
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** The host's voice setup store: key handling, origin changes and revisions. */
class VoiceSetupStoreTest {
    private val dir = Files.createTempDirectory("voice-store")
    private val keys = FileVoiceKeyStore(dir.resolve("voice-speech-key"))
    private val store = VoiceSetupStore(FileVoiceSetupPersistence(dir.resolve("voice-setup.json")), keys) { NOW }

    @Test
    fun theKeyIsWrittenOnlyToItsOwnFileAndNeverReadBack(): Unit = runBlocking {
        val status = store.write(command(PRIMARY, HostVoiceKeyChange.Replace(SecretWriteValue(KEY)), both))
        assertTrue(status.hasApiKey)
        assertFalse(KEY in voiceWire(HostVoiceSetupStatus.serializer(), status).toString())
        assertFalse(KEY in Files.readString(dir.resolve("voice-setup.json")))
        assertEquals(KEY, keys.read("https://speech.example.invalid:443"))
        assertFalse(KEY in store.snapshot().toString())
    }

    @Test
    fun aNewOriginDropsTheKeyAndTheGrantsItDoesNotRestate(): Unit = runBlocking {
        store.write(command(PRIMARY, HostVoiceKeyChange.Replace(SecretWriteValue(KEY)), both))
        val moved = store.write(command(OTHER, HostVoiceKeyChange.Keep, HostVoiceGrants()))
        assertFalse(moved.hasApiKey)
        assertEquals(HostVoiceGrants(), moved.consent.grants)
        assertNull(moved.consent.grantedAtMs)
        assertNull(keys.read("https://speech.example.invalid:443"))
        assertNull(keys.read("https://other-speech.example.invalid:443"))
    }

    @Test
    fun aKeyLeftForAnotherOriginIsNeverUsed(): Unit = runBlocking {
        store.write(command(PRIMARY, HostVoiceKeyChange.Keep, both))
        // As if a crash left a key file from an earlier setup on another server.
        keys.write("https://other-speech.example.invalid:443", KEY)
        assertFalse(store.status().hasApiKey)
        assertNull(store.snapshot()?.apiKey)
    }

    @Test
    fun aStaleRevisionWritesNothing(): Unit = runBlocking {
        val first = store.write(command(PRIMARY, HostVoiceKeyChange.Keep, both))
        store.write(command(PRIMARY, HostVoiceKeyChange.Keep, both))
        val refusal = assertFailsWith<VoiceRefusal> {
            store.write(command(OTHER, HostVoiceKeyChange.Keep, both, expectedRevision = first.revision))
        }
        assertEquals(HostVoiceErrorCode.REVISION_CONFLICT, refusal.code)
        assertEquals(PRIMARY.value, store.status().baseUrl)
    }

    @Test
    fun revisionsKeepCountingAcrossAClear(): Unit = runBlocking {
        val first = store.write(command(PRIMARY, HostVoiceKeyChange.Keep, both))
        store.clear(first.revision)
        val again = store.write(command(PRIMARY, HostVoiceKeyChange.Keep, both))
        assertTrue(again.revision != first.revision, "an old revision never names a newer setup")
        assertFailsWith<VoiceRefusal> { store.clear(first.revision) }
    }

    @Test
    fun keepingAKeyOverPlainHttpOffLoopbackIsRefused(): Unit = runBlocking {
        val refusal = assertFailsWith<VoiceRefusal> {
            val plainHttp = SpeechEndpointUrl("http://speech.example.invalid")
            store.write(command(plainHttp, HostVoiceKeyChange.Replace(SecretWriteValue(KEY)), both))
        }
        assertEquals(HostVoiceErrorCode.INSECURE_TRANSPORT, refusal.code)
        assertNull(keys.read("http://speech.example.invalid:80"))
        assertFalse(store.status().configured)
    }

    @Test
    fun aFailedSetupWriteLeavesTheKeyAsItWas(): Unit = runBlocking {
        val persistence = FailingPersistence()
        val memoryKeys = InMemoryVoiceKeyStore()
        val failing = VoiceSetupStore(persistence, memoryKeys) { NOW }
        persistence.fail = true
        assertFailsWith<IllegalStateException> { failing.write(command(PRIMARY, HostVoiceKeyChange.Replace(SecretWriteValue(KEY)), both)) }
        assertNull(memoryKeys.read(PRIMARY_ORIGIN), "a key without its setup is removed again")
        persistence.fail = false
        failing.write(command(PRIMARY, HostVoiceKeyChange.Replace(SecretWriteValue(KEY)), both))
        persistence.fail = true
        assertFailsWith<IllegalStateException> { failing.write(command(OTHER, HostVoiceKeyChange.Keep, both)) }
        assertFailsWith<IllegalStateException> { failing.write(command(PRIMARY, HostVoiceKeyChange.Remove, both)) }
        assertFailsWith<IllegalStateException> {
            failing.write(command(PRIMARY, HostVoiceKeyChange.Replace(SecretWriteValue("another-placeholder")), both))
        }
        assertFailsWith<IllegalStateException> { failing.clear(null) }
        assertEquals(KEY, memoryKeys.read(PRIMARY_ORIGIN))
        assertTrue(failing.status().hasApiKey)
    }

    /** Keeps the setup in memory, failing every write while [fail] is set. */
    private class FailingPersistence : VoiceSetupPersistence {
        var fail = false
        private val stored = InMemoryVoiceSetupPersistence()

        override fun read(): VoiceSetupState = stored.read()

        override fun write(state: VoiceSetupState) {
            check(!fail) { "disk full" }
            stored.write(state)
        }
    }

    private fun command(url: SpeechEndpointUrl, key: HostVoiceKeyChange, grants: HostVoiceGrants, expectedRevision: String? = null) =
        HostVoiceSetupCommand(
            HostVoiceEndpointChoice(url, SpeechModelName("stt-model-a"), SpeechModelName("tts-model-a"), SpeechVoiceName("voice-a")),
            key, grants, expectedRevision,
        )

    private companion object {
        const val NOW = 1_767_225_600_000L
        const val KEY = "placeholder-key-not-real"
        const val PRIMARY_ORIGIN = "https://speech.example.invalid:443"
        val PRIMARY = SpeechEndpointUrl("https://speech.example.invalid")
        val OTHER = SpeechEndpointUrl("https://other-speech.example.invalid")
        val both = HostVoiceGrants(audioForTranscription = true, textForSynthesis = true)
    }
}
