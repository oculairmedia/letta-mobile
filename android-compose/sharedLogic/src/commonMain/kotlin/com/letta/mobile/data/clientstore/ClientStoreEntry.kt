package com.letta.mobile.data.clientstore

/**
 * Why a client may keep a value at all. Clients hold no user state: preferences, pins, layout,
 * drafts and history come from the host's shell state document. The client store keeps only what
 * a client needs before it can reach a host, caches that rebuild from the host, and the few facts
 * only the operating system of this device can own.
 */
sealed interface ClientStoreKind {
    /** Needed before a host is reachable: the host address or pairing, this device's identity key or token. */
    data object ConnectionBootstrap : ClientStoreKind

    /** Wiping it loses nothing the user created or chose; it rebuilds from the host. Cleared by [DisposableCache.clearAll]. */
    data object DisposableCache : ClientStoreKind

    /** A per-device fact the host cannot own; [justification] says why, and may not be blank. */
    data class DeviceFact(val justification: String) : ClientStoreKind {
        init {
            require(justification.isNotBlank()) { "A device fact needs a justification" }
        }
    }
}

/**
 * One value the client store may keep. [name] is the exact storage key, or with [isPrefix] the fixed
 * start of a key family whose members come from [keyFor].
 */
class ClientStoreEntry internal constructor(
    val name: String,
    val kind: ClientStoreKind,
    val summary: String,
    val isPrefix: Boolean = false,
) {
    /** The exact storage key of a single entry. */
    val storageKey: String
        get() {
            check(!isPrefix) { "$name is a key family; use keyFor" }
            return name
        }

    /** The member of this key family for [suffix]. */
    fun keyFor(suffix: String): String {
        check(isPrefix) { "$name is a single key; use storageKey" }
        return name + suffix
    }

    /** Whether [key] is this entry or a member of this family. */
    fun matches(key: String): Boolean = if (isPrefix) key.startsWith(name) else key == name

    override fun toString(): String = "ClientStoreEntry($name, $kind)"
}

/** A cache in the client store: [clearAll] wipes it, and the next read rebuilds from the host. */
interface DisposableCache {
    /** The declared entry this cache keeps. */
    val entry: ClientStoreEntry

    /** Wipe every value of this cache. */
    suspend fun clearAll()
}
