package com.letta.mobile.ui.shell.pages.home

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.home.greeting.GreetingConnection

/**
 * The host's part of Home's greeting: how its link to the backend stands ([connection]; the mark
 * shows it and the cast huddles while it is unsettled), and the [hour] (0..23) the line is written
 * for, null to read the device clock.
 */
@Immutable
data class HomeGreetingOptions(
    val connection: GreetingConnection = GreetingConnection.CONNECTED,
    val hour: Int? = null,
)
