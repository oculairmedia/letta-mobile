package com.letta.mobile.desktop

import java.util.concurrent.atomic.AtomicBoolean

/** Defers modal UI until Compose has unwound, and allows only one fatal dialog per process. */
internal class DesktopCrashShutdown(
    private val dispatch: (() -> Unit) -> Unit,
    private val terminate: () -> Unit,
) {
    private val requested = AtomicBoolean(false)

    fun request(showDialog: () -> Unit) {
        if (!requested.compareAndSet(false, true)) return
        dispatch {
            try {
                showDialog()
            } finally {
                terminate()
            }
        }
    }
}
