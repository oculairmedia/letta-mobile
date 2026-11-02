package com.letta.mobile.data.beads

/** Recognized event operations; the raw wire value remains available on each event. */
enum class BeadsEventKind {
    CREATE, UPDATE, CLOSE, DELETE, DEP_ADD, DEP_REMOVE, COMMENT, UNKNOWN;

    /** Classifies an operation without rejecting future values. */
    companion object {
        fun fromWire(value: String): BeadsEventKind = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: UNKNOWN
    }
}

/** Common issue statuses; servers may also supply workspace-specific values. */
enum class BeadsIssueStatus {
    OPEN, IN_PROGRESS, BLOCKED, CLOSED, UNKNOWN;

    /** Classifies a status without discarding an unrecognized wire value. */
    companion object {
        fun fromWire(value: String): BeadsIssueStatus = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: UNKNOWN
    }
}
