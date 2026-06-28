package com.letta.mobile.runtime.iroh

import com.letta.mobile.util.Telemetry

actual object IrohAndroidInit {
    actual fun install(context: Any?) {
        Telemetry.event("Iroh", "android_init.skipped", "reason" to "not_android_native")
    }
}
