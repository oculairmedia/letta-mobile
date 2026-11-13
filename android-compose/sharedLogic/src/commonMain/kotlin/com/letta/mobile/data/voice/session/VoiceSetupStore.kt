package com.letta.mobile.data.voice.session

import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.data.voice.SpeechEndpointUrl
import com.letta.mobile.data.voice.VoiceEndpointSettings
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.consent.EndpointOrigin
import com.letta.mobile.data.voice.consent.VoiceEndpointGrant
import com.letta.mobile.data.voice.consent.VoiceEndpointGrants
import com.letta.mobile.data.voice.consent.VoiceEndpointPayload
import com.letta.mobile.data.voice.endpoint.SpeechApiKey
import com.letta.mobile.data.voice.endpoint.SpeechEndpointAddress
import com.letta.mobile.data.voice.endpoint.SpeechEndpointClient
import com.letta.mobile.data.voice.endpoint.SpeechEndpointConfig
import com.letta.mobile.data.voice.endpoint.SpeechEndpointException
import com.letta.mobile.data.voice.endpoint.SpeechTransportPolicy
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the person configured for voice mode, as stored. Never carries the API key itself. */
data class VoiceSetup(
    /** The speech endpoint and its model names. */
    val endpoint: VoiceEndpointSettings = VoiceEndpointSettings(),
    /** The destination grants the person recorded. */
    val grants: VoiceEndpointGrants = VoiceEndpointGrants(),
    /** Whether an API key is kept in the secure store. */
    val hasApiKey: Boolean = false,
) {
    /** Whether the person agreed to send both audio and reply text to the configured endpoint. */
    val consented: Boolean
        get() {
            val url = endpoint.baseUrl ?: return false
            return VoiceEndpointPayload.entries.all { grants.allows(url, it) }
        }

    /** What is still missing before voice mode can start; null when it can. */
    val gap: VoiceSetupGap?
        get() {
            val url = endpoint.baseUrl ?: return VoiceSetupGap.NO_ENDPOINT
            return when {
                VoiceEndpointRules.problem(url.value, withKey = false) != null -> VoiceSetupGap.INVALID_ENDPOINT
                !consented -> VoiceSetupGap.NO_CONSENT
                else -> null
            }
        }
}

/**
 * What the person is editing in the Voice settings. [apiKey] null keeps the stored key, blank
 * removes it. Its [toString] never prints the key.
 */
class VoiceSetupDraft(
    /** The base URL as typed. */
    val baseUrl: String,
    /** The API key as typed: null keeps the stored one, blank removes it. */
    val apiKey: String? = null,
    /** Whether the consent toggle is on. */
    val consent: Boolean = false,
) {
    override fun toString(): String = "VoiceSetupDraft(keyTyped=${apiKey != null}, consent=$consent)"
}

/** The rules a speech endpoint address must meet, the same ones the endpoint client enforces. */
object VoiceEndpointRules {
    /**
     * Why [url] cannot be used, or null when it can: INVALID_ENDPOINT unless it is a plain http(s)
     * base URL (no query, fragment, user info or whitespace); with [withKey], INSECURE_TRANSPORT
     * unless it is https or plain http to a loopback host, since a key must never travel unencrypted.
     */
    fun problem(url: String, withKey: Boolean): VoiceErrorCode? {
        val address = SpeechEndpointUrl(url.trim())
        if (address.value.isEmpty() || EndpointOrigin.of(address) == null) return VoiceErrorCode.INVALID_ENDPOINT
        val resolved = SpeechEndpointAddress.resolve(address, emptyList()) ?: return VoiceErrorCode.INVALID_ENDPOINT
        return if (withKey && !SpeechTransportPolicy.protectsKey(resolved)) VoiceErrorCode.INSECURE_TRANSPORT else null
    }
}

/**
 * Voice mode's stored setup: the endpoint address and the destination grants in [settings], each in
 * its own value, and the API key only in [secrets], the store the app keeps server tokens in. With
 * no [secrets] ([keepsApiKey] false) a key is never stored anywhere. Nothing here logs.
 */
class VoiceSetupStore(
    private val settings: SecureSettingsStore,
    private val secrets: SecureSettingsStore?,
) {
    private val state = MutableStateFlow(read())

    /** The stored setup, updated by every [save]. */
    val setup: StateFlow<VoiceSetup> = state.asStateFlow()

    /** Whether this platform can keep an API key (in its secure store). */
    val keepsApiKey: Boolean get() = secrets != null

    /** The stored key, read only to build a request. */
    fun apiKey(): SpeechApiKey? = storedKey()?.let(::SpeechApiKey)

    /**
     * Validates and stores [draft]: the address, a grant for both payloads to its origin when consent
     * is on (and none otherwise, so a changed address never inherits consent), and the key. Returns
     * the address problem and stores nothing when there is one.
     */
    fun save(draft: VoiceSetupDraft): VoiceErrorCode? {
        val key = keyAfter(draft)
        VoiceEndpointRules.problem(draft.baseUrl, withKey = key != null)?.let { return it }
        val url = SpeechEndpointUrl(draft.baseUrl.trim())
        settings.putString(KEY_BASE_URL, url.value)
        val origin = EndpointOrigin.of(url)
        val consented = if (draft.consent) origin else null
        val grants = consented?.let { granted -> VoiceEndpointPayload.entries.map { VoiceEndpointGrant(granted, it) } }.orEmpty()
        settings.putString(KEY_GRANTS, grants.joinToString(GRANT_SEPARATOR) { "${it.payload.name}$FIELD_SEPARATOR${it.origin.value}" })
        writeKey(draft.apiKey)
        state.value = read()
        return null
    }

    /**
     * Whether [draft]'s endpoint answers, checked through the endpoint client without sending audio
     * or text (so it needs no consent): null when it does, else the address problem or the failure
     * code (UNREACHABLE, TIMED_OUT, UNAUTHORIZED, INSECURE_TRANSPORT). Uses and then closes [engine].
     */
    suspend fun check(draft: VoiceSetupDraft, engine: HttpClientEngine): VoiceErrorCode? {
        val key = keyAfter(draft)
        VoiceEndpointRules.problem(draft.baseUrl, withKey = key != null)?.let {
            engine.close()
            return it
        }
        val config = SpeechEndpointConfig(SpeechEndpointUrl(draft.baseUrl.trim()), key?.let(::SpeechApiKey))
        val client = SpeechEndpointClient(engine, config)
        return try {
            client.checkReachable()
            null
        } catch (failure: SpeechEndpointException) {
            failure.code
        } finally {
            client.close()
            engine.close()
        }
    }

    /** The key a [draft] would leave stored: the typed one, none when blanked, else the stored one. */
    fun keyAfter(draft: VoiceSetupDraft): String? = when {
        secrets == null -> null
        draft.apiKey == null -> storedKey()
        else -> draft.apiKey.trim().takeIf { it.isNotEmpty() }
    }

    private fun writeKey(typed: String?) {
        val store = secrets ?: return
        if (typed == null) return
        val key = typed.trim()
        if (key.isEmpty()) store.remove(KEY_API_KEY) else store.putString(KEY_API_KEY, key)
    }

    private fun storedKey(): String? = secrets?.getString(KEY_API_KEY)?.takeIf { it.isNotBlank() }

    private fun read(): VoiceSetup = VoiceSetup(
        endpoint = VoiceEndpointSettings(baseUrl = settings.getString(KEY_BASE_URL)?.takeIf { it.isNotBlank() }?.let(::SpeechEndpointUrl)),
        grants = VoiceEndpointGrants(parseGrants(settings.getString(KEY_GRANTS).orEmpty())),
        hasApiKey = storedKey() != null,
    )

    private fun parseGrants(text: String): Set<VoiceEndpointGrant> = text.split(GRANT_SEPARATOR).mapNotNull { entry ->
        val payload = VoiceEndpointPayload.entries.firstOrNull { entry.startsWith("${it.name}$FIELD_SEPARATOR") }
        payload?.let { VoiceEndpointGrant(EndpointOrigin(entry.removePrefix("${it.name}$FIELD_SEPARATOR")), it) }
    }.toSet()

    /** The stored keys. */
    companion object {
        /** The endpoint's base URL. */
        const val KEY_BASE_URL: String = "voice.endpoint.baseUrl"

        /** The destination grants, kept apart from the settings so an export never carries consent. */
        const val KEY_GRANTS: String = "voice.endpoint.grants"

        /** The API key; hosts must register it as a sensitive key of their secure store. */
        const val KEY_API_KEY: String = "voice.endpoint.apiKey"

        private const val GRANT_SEPARATOR = "\n"
        private const val FIELD_SEPARATOR = " "
    }
}
