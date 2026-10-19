package com.letta.mobile.ui.runtime

import androidx.compose.runtime.staticCompositionLocalOf
import com.letta.mobile.data.runtime.AgentDefaultsRepository

/** The host's per-agent defaults (cwd, and permission mode). Null where none is bound. */
val LocalAgentDefaults = staticCompositionLocalOf<AgentDefaultsRepository?> { null }
