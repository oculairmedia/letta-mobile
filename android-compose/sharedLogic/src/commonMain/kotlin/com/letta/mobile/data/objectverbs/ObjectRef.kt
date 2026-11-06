package com.letta.mobile.data.objectverbs

import kotlinx.serialization.Serializable

/**
 * Canonical identity within the caller's bound source, independent of lens or view mount.
 * Wire [kind] and [id] stay open strings so future kinds decode. Known callers use the typed
 * factories; a raw reference grants no authority and an unknown kind renders nothing.
 */
@Serializable
data class ObjectRef(val kind: String, val id: String) {
    /** Typed factories are imported from objectverbs.refs, keeping descriptors storage-independent. */
    companion object
}
