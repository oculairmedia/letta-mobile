package com.letta.mobile.data.voice.catalog

import com.letta.mobile.data.voice.VoiceErrorCode

/**
 * What a speech server offers: the model ids it lists, and its voices where it lists them. [voices]
 * is null when the server does not list voices (the voice listing is an extension many servers add,
 * not part of the OpenAI API), so [SpeechChoices.voices] offers OpenAI's documented voices instead.
 */
data class SpeechCatalog(
    /** The model ids, in the server's order. */
    val models: List<String>,
    /** The voice ids, in the server's order; null when the server lists none. */
    val voices: List<String>?,
)

/**
 * Where a speech server's [SpeechCatalog] comes from: the server itself, asked directly with this
 * device's own key, or the host, which asks the shared server with the key it keeps. Settings read
 * either through this one seam, so neither knows how the other reaches the server.
 */
fun interface SpeechCatalogSource {
    /** The catalog; fails with [SpeechCatalogException] when the server cannot be listed. */
    suspend fun load(): SpeechCatalog
}

/**
 * The catalog could not be listed. It carries only the fixed [code]: never the server's address,
 * the key or anything the server replied.
 */
class SpeechCatalogException(
    /** Why: UNREACHABLE, UNRESOLVED, UNAUTHORIZED, REJECTED, BAD_RESPONSE, TIMED_OUT, INVALID_ENDPOINT, INSECURE_TRANSPORT and the like. */
    val code: VoiceErrorCode,
    /** The failure it stands for, itself carrying no address, key or reply. */
    cause: Throwable? = null,
) : Exception("Speech catalog failed: $code", cause)
