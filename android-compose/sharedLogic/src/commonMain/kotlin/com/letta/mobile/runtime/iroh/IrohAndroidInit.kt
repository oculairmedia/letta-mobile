package com.letta.mobile.runtime.iroh

/**
 * Installs the Android application context into Iroh's JNI so its
 * DNS resolver can reach Android's LinkProperties. Call once before any
 * Endpoint.bind. No-op on non-Android platforms.
 */
expect object IrohAndroidInit {
    fun install(context: Any?)
}
