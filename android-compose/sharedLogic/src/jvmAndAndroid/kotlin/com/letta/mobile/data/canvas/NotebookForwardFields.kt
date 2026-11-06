package com.letta.mobile.data.canvas

import com.letta.mobile.data.serialization.LettaJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/*
 * Canvas metadata and notebook items are JSON strings inside the shared Automerge document, so a
 * newer build on another device can add fields this build does not know. Reading ignores them, and
 * writing keeps them: this build replaces only the fields it declares.
 */

/** Decode a stored metadata or item string, ignoring fields a newer build added. */
internal fun <T> KSerializer<T>.decodeStored(raw: String): T = LettaJson.persisted.decodeFromString(this, raw)

/**
 * Encode [value] in the stored format, keeping any field of [stored] that this serializer does not
 * declare. With nothing unknown stored, the output is exactly the plain encoding.
 */
internal fun <T> KSerializer<T>.encodeKeepingUnknown(value: T, stored: String?): String {
    val known = Json.encodeToJsonElement(this, value).jsonObject
    val declared = (0 until descriptor.elementsCount).mapTo(mutableSetOf(), descriptor::getElementName)
    val unknown = stored?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
        ?.filterKeys { it !in declared }.orEmpty()
    return if (unknown.isEmpty()) known.toString() else JsonObject(known + unknown).toString()
}
