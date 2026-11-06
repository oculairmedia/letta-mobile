package com.letta.mobile.data.pins

import com.letta.mobile.data.objectverbs.ObjectRef
import kotlinx.serialization.Serializable

/** A device-local reference in a trusted backend namespace. Shortcuts use [DEVICE_SOURCE]. */
@Serializable
data class ScopedObjectRef(val source: String, val objectRef: ObjectRef) {
    init { require(source.isNotBlank()) }

    companion object {
        const val DEVICE_SOURCE = "device:shortcuts"
    }
}

/** Every distinct read/decode attempt is retained until the source's import is verified. */
@Serializable
data class PinImportSnapshot(val raw: String?, val entries: List<String> = emptyList(), val error: String? = null)

/** Recoverable raw snapshot and parse result for one legacy backing; null raw means absent. */
@Serializable
data class PinImportRecord(
    val key: String,
    val raw: String? = null,
    val entries: List<String> = emptyList(),
    val error: String? = null,
    val captured: Boolean = false,
    val complete: Boolean = false,
    val resolved: Map<String, ScopedObjectRef> = emptyMap(),
    val snapshots: List<PinImportSnapshot> = emptyList(),
)

/** One durable journal contains membership, explicit-unpin tombstones, snapshots and checkpoints. */
@Serializable
data class PinJournal(
    val version: Int = 1,
    val pins: List<ScopedObjectRef> = emptyList(),
    val tombstones: Set<ScopedObjectRef> = emptySet(),
    val imports: List<PinImportRecord> = emptyList(),
    val homeDefaultsConsidered: Boolean = false,
    // Null denotes an older journal without provenance; empty records an explicitly preserved order.
    val legacyOrderedPins: List<ScopedObjectRef>? = null,
)

/** Raw access is separate from parsing so even decode failures retain a recoverable snapshot. */
class LegacyPinSource(
    val key: String,
    val read: () -> String?,
    val decode: (String) -> List<String>,
)
