package com.letta.mobile.ui.screens.config

/**
 * Where the Settings screen's integration rows lead. One value instead of a
 * callback per row keeps ConfigScreen's signature stable as rows are added
 * (letta-mobile-w4q4p.6.1 added Providers).
 */
data class ConfigDestinations(
    val onSystemAccess: () -> Unit = {},
    val onVibesyncDebug: () -> Unit = {},
    val onCanvasDebug: () -> Unit = {},
    val onProviders: () -> Unit = {},
)
