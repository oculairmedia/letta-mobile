package com.letta.mobile.data.shellstate.wire

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One entry change of a `shell.state.propose`: set [key] to [value], or remove it when [value] is null.
 * [expectedRevision] is the revision the caller's copy of the entry was at (0 when it had none), so a
 * writer only ever conflicts with another writer of the same key.
 */
data class ShellStateChange(
    val key: String,
    val value: JsonElement?,
    val expectedRevision: Long,
)

/** This change as the wire object: a removal carries no `value`. */
fun ShellStateChange.toJson(): JsonObject = buildJsonObject {
    put(ShellStateWire.KEY, key)
    value?.let { put(ShellStateWire.VALUE, it) }
    put(ShellStateWire.EXPECTED_REVISION, expectedRevision)
}
