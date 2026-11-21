package com.letta.mobile.data.shellstate

/** How one proposal ended. */
enum class ShellStateEditOutcome {
    /** The host accepted it (or it changed nothing). */
    APPLIED,

    /** Another writer changed one of the entries first; the document was read again. */
    CONFLICT,

    /** The host is out of reach or the document is not read yet; nothing was sent or queued. */
    OFFLINE,

    /** The host does not keep shared shell state. */
    UNSUPPORTED,

    /** The host refused the proposal (malformed, too large, unreadable store or not allowed). */
    REFUSED,
}
