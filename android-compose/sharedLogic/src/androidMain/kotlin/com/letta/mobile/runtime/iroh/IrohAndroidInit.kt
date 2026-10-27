package com.letta.mobile.runtime.iroh

import com.letta.mobile.util.attr
import android.content.Context
import com.letta.mobile.util.Telemetry

/** Installs the physical Android context before native Iroh operations. */
object IrohAndroidInit {
    fun install(context: Context) {
        runCatching {
            computer.iroh.IrohAndroid.installAndroidContext(context)
        }.onFailure { t: Throwable ->
            Telemetry.event("Iroh", "android_init.failed", "error" attr (t.message ?: t::class.simpleName.orEmpty()))
        }.onSuccess {
            Telemetry.event("Iroh", "android_init.ok")
        }
    }
}
