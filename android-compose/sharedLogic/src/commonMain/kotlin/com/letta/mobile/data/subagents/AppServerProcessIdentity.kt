package com.letta.mobile.data.subagents

/** Host proof of the App Server process, never a connection generation or a PID alone. */
fun interface AppServerProcessIdentity {
    /** Opaque identity stable for one process lifetime; null when the host cannot prove it. */
    fun current(): String?
}
