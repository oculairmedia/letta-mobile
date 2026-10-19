package com.letta.mobile.data.repository.modelcontrol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Lenient readers for the provider token endpoints' JSON. */

/** A non-blank string field. */
internal fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }

/** A number, or a numeric string (OpenAI sends `interval` as either). */
internal fun JsonObject.number(key: String): Double? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    return primitive.doubleOrNull ?: primitive.contentOrNull?.trim()?.toDoubleOrNull()
}

/** A whole number of seconds. */
internal fun JsonObject.seconds(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull ?: number(key)?.toLong()

/** A field the flow cannot go on without; its absence names the field, never a value. */
internal fun JsonObject.require(key: String, kind: ProviderOAuthKind): String =
    text(key) ?: throw ProviderSignInException("${kind.label} sent an incomplete answer (no $key)")
