package com.letta.mobile.runtime.iroh

import android.content.Context
import com.letta.mobile.util.Telemetry

actual object IrohAndroidInit {
    actual fun install(context: Any?) {
        if (context !is Context) {
            Telemetry.event("Iroh", "android_init.skipped", "reason" to "not_a_context")
            return
        }
        runCatching<Unit> {
            computer.iroh.IrohAndroid.installAndroidContext(context)
        }.onFailure { t: Throwable ->
            Telemetry.event("Iroh", "android_init.failed", "error" to (t.message ?: t::class.simpleName.orEmpty()))
        }.onSuccess {
            Telemetry.event("Iroh", "android_init.ok")
        }
    }
}
