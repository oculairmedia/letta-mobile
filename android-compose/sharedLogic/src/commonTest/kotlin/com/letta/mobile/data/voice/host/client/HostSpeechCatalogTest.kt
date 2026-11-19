package com.letta.mobile.data.voice.host.client

import com.letta.mobile.data.voice.catalog.SpeechCatalogException
import com.letta.mobile.data.voice.host.HostVoiceErrorCode
import com.letta.mobile.data.voice.host.HostVoiceWire
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shared server's catalog read through the host over every `voice.models.list` contract case:
 * listed voices pass through, voices the host only echoes from the stored setup read as unlisted,
 * and a host failure becomes its voice code. The device sends nothing but the listing request.
 */
class HostSpeechCatalogTest {
    private val link = FakeHostVoiceLink()
    private val catalog = HostSpeechCatalog(IrohHostVoiceEndpoint(link))

    @Test
    fun aServerThatListsVoicesPassesThemThrough() = runTest {
        link.answer = { _, _ -> reply(VoiceFixtures.case(HostVoiceWire.MODELS_LIST, "server lists its voices").response) }
        val listed = catalog.load()
        assertEquals(listOf("stt-model-a", "tts-model-a"), listed.models)
        assertEquals(listOf("voice-a", "voice-b"), listed.voices)
        assertEquals(listOf(HostVoiceWire.MODELS_LIST), link.methods())
        assertTrue(link.calls.single().second.isEmpty(), "the listing carries no params, so no key or address")
    }

    @Test
    fun voicesEchoedFromTheStoredSetupReadAsUnlisted() = runTest {
        link.answer = { _, _ -> reply(VoiceFixtures.case(HostVoiceWire.MODELS_LIST, "server lists no voices").response) }
        val listed = catalog.load()
        assertEquals(listOf("tts-model-a"), listed.models)
        assertNull(listed.voices)
    }

    @Test
    fun everyContractCaseReadsAsACatalogOrItsVoiceCode() = runTest {
        VoiceFixtures.of(HostVoiceWire.MODELS_LIST).forEach { case ->
            link.answer = { _, _ -> reply(case.response) }
            if (case.succeeded) {
                catalog.load()
            } else {
                val failure = assertFailsWith<SpeechCatalogException>(case.toString()) { catalog.load() }
                assertEquals(HostVoiceErrorCode.fromWire(case.error).voiceCode, failure.code, case.toString())
            }
        }
    }
}
