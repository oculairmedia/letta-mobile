package com.letta.mobile.data.shellstate.wire

/**
 * Names, limits and codes of the shell-state v1 contract, shared by host and client. The host keeps one
 * versioned document of shell and user state for every paired device and pushes each accepted change to
 * all of them; a host without [CAPABILITY] has no shared shell state.
 */
object ShellStateWire {
    /** Advertised by a host that serves every v1 method and pushes [FRAME_TYPE]. */
    const val CAPABILITY: String = "shell_state_v1"

    /** Reads the document. */
    const val GET: String = "shell.state.get"

    /** Changes entries of the document when each change's expected revision is current. */
    const val PROPOSE: String = "shell.state.propose"

    /** Every v1 method. */
    val METHODS: Set<String> = setOf(GET, PROPOSE)

    /** Frame type of the push the host sends every connection after each accepted change. */
    const val FRAME_TYPE: String = "shell_state_updated"

    /** Most entries the document may hold. */
    const val MAX_ENTRIES: Int = 512

    /** Most changes one proposal may carry. */
    const val MAX_CHANGES: Int = 64

    /** Longest entry key accepted. */
    const val MAX_KEY_CHARS: Int = 128

    /** What an entry key looks like: lowercase words joined by dots, hyphens or underscores, like `appearance.theme`. */
    val KEY_PATTERN: Regex = Regex("[a-z0-9][a-z0-9._-]*")

    /**
     * The key namespaces a proposal may write: a key must start with one of these prefixes, and anything else is
     * refused as [INVALID_REQUEST]. The list grows only when an area of client state moves onto the document (one
     * namespace per area), so a paired chat device can never write arbitrary keys, and nothing outside a moved area
     * (and never a secret) has a place in it.
     */
    val KEY_NAMESPACES: List<String> = listOf("appearance.")

    /** Whether [key] lies in one of the [KEY_NAMESPACES] with something after the prefix. */
    fun isAllowedKey(key: String): Boolean = KEY_NAMESPACES.any { key.length > it.length && key.startsWith(it) }

    /** Largest encoded value of one entry, in UTF-8 bytes. */
    const val MAX_VALUE_BYTES: Int = 8 * 1024

    /** Largest encoded document (what is stored and pushed), in UTF-8 bytes. */
    const val MAX_DOCUMENT_BYTES: Int = 256 * 1024

    /** The refusal of a proposal whose expected revision is no longer current for some entry. */
    const val REVISION_CONFLICT: String = "revision_conflict"

    /** The refusal of a malformed proposal. */
    const val INVALID_REQUEST: String = "invalid_request"

    /** The refusal of a proposal that would exceed a size limit. */
    const val TOO_LARGE: String = "too_large"

    /** The refusal when the host cannot read its stored document; nothing is changed. */
    const val STATE_UNREADABLE: String = "state_unreadable"

    /** Param of `shell.state.propose`: the list of changes. */
    const val CHANGES: String = "changes"

    /** Field of a change: the entry key. */
    const val KEY: String = "key"

    /** Field of a change: the new value, or null (or absent) to remove the entry. */
    const val VALUE: String = "value"

    /** Field of a change: the revision the caller's copy of the entry was at; 0 when it had none. */
    const val EXPECTED_REVISION: String = "expected_revision"

    /** The client id recorded for a change made through the host's own in-process router (the on-device runtime). */
    const val LOCAL_CLIENT_ID: String = "host-local"
}

/** A shell-state failure as the client sees it; any code it does not know reads as [UNKNOWN]. */
enum class ShellStateError {
    /** Another writer changed an entry first. */
    REVISION_CONFLICT,

    /** The proposal was malformed. */
    INVALID_REQUEST,

    /** The proposal would exceed a size limit. */
    TOO_LARGE,

    /** This device may not read or change the document (not authorized, or bounded to some agents). */
    FORBIDDEN,

    /** The host cannot read its stored document. */
    STATE_UNREADABLE,

    /** The host does not serve the method. */
    UNSUPPORTED,

    /** No answer: the link failed or the reply was unreadable. */
    UNREACHABLE,

    /** A code this client does not know. */
    UNKNOWN,
    ;

    companion object {
        /** The error for the host's `error` string; null reads as [UNREACHABLE]. */
        fun fromWire(code: String?): ShellStateError = when {
            code == null -> UNREACHABLE
            code == ShellStateWire.REVISION_CONFLICT -> REVISION_CONFLICT
            code == ShellStateWire.INVALID_REQUEST -> INVALID_REQUEST
            code == ShellStateWire.TOO_LARGE -> TOO_LARGE
            code == ShellStateWire.STATE_UNREADABLE -> STATE_UNREADABLE
            code.startsWith("forbidden") -> FORBIDDEN
            code.startsWith("Unknown method") -> UNSUPPORTED
            else -> UNKNOWN
        }
    }
}
