package com.letta.mobile.performance

import android.app.Application

object DebugPerformanceMonitor {
    // Preserve the debug variant's startup API; release builds must not install debug monitoring.
    @Suppress("UnusedParameter")
    fun install(application: Application) = Unit
}
