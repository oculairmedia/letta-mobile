package com.letta.mobile.data.controller.node.iroh.voice

import com.letta.mobile.data.voice.host.HostVoiceErrorCode
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** A string field, null when absent or null; any other type is `voice.invalid_request`. */
internal fun JsonObject.voiceString(key: String): String? {
    val value = this[key]
    if (value == null || value == JsonNull) return null
    val primitive = value as? JsonPrimitive
    return primitive?.takeIf { it.isString }?.content ?: voiceInvalid()
}

/** A model, voice or language name: trimmed, and absent when empty. */
internal fun JsonObject.voiceName(key: String): String? {
    return voiceString(key)?.trim()?.takeIf { it.isNotEmpty() }
}

/** A boolean field, null when absent or null; any other type is `voice.invalid_request`. */
internal fun JsonObject.voiceFlag(key: String): Boolean? {
    val value = this[key]
    if (value == null || value == JsonNull) return null
    val primitive = (value as? JsonPrimitive)?.takeUnless { it.isString }
    return primitive?.booleanOrNull ?: voiceInvalid()
}

/** Refuses the request as malformed. */
internal fun voiceInvalid(): Nothing = voiceRefuse(HostVoiceErrorCode.INVALID_REQUEST)

/** Refuses the request with [code]. */
internal fun voiceRefuse(code: HostVoiceErrorCode): Nothing = throw VoiceRefusal(code)
