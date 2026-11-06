package com.letta.mobile.data.canvas

import com.letta.mobile.data.serialization.LettaJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
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
 * declare, at the top level and inside declared nested objects (such as a canvas's `acl`). With
 * nothing unknown stored, the output is exactly the plain encoding.
 */
internal fun <T> KSerializer<T>.encodeKeepingUnknown(value: T, stored: String?): String {
    val known = Json.encodeToJsonElement(this, value).jsonObject
    val previous = stored?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
        ?: return known.toString()
    return keepUnknown(known, previous, descriptor).toString()
}

/**
 * [known] plus [previous]'s fields that [descriptor] does not declare. A declared field that is an
 * object on both sides and declared as a class is merged the same way. A declared field this build
 * omitted or changed to a non-object is this build's value: only undeclared fields are kept.
 */
private fun keepUnknown(known: JsonObject, previous: JsonObject, descriptor: SerialDescriptor): JsonObject {
    val declared = (0 until descriptor.elementsCount).associateBy(descriptor::getElementName)
    val merged = known.mapValues { (name, value) ->
        val nested = declared[name]?.let(descriptor::getElementDescriptor)?.takeIf { it.kind == StructureKind.CLASS }
        val before = previous[name]
        if (nested != null && value is JsonObject && before is JsonObject) keepUnknown(value, before, nested) else value
    }
    val unknown = previous.filterKeys { it !in declared }
    return if (unknown.isEmpty()) JsonObject(merged) else JsonObject(merged + unknown)
}
