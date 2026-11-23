package com.letta.mobile.ui.shell.pages.home

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.home.HomeAgentRef

/** Who the greeting may put on stage, in order, and how many conversation updates each had today. */
@Immutable
internal data class GreetingCast(val agents: List<HomeAgentRef>, val updatesToday: Map<String, Int> = emptyMap())
