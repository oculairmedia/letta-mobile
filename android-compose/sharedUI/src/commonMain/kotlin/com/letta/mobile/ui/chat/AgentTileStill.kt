package com.letta.mobile.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import com.letta.mobile.avatar.core.model.MascotIdentity
import com.letta.mobile.ui.mascot.LocalMascotHost
import com.letta.mobile.ui.mascot.MASCOT_TILE_OVERSCALE

/**
 * [identity]'s captured still, or null until it lands. Shell surfaces that trace a tile's character
 * (the thinking aura) read it here, beside [AgentOrb], rather than reaching into the mascot package.
 */
@Composable
fun agentTileStill(identity: MascotIdentity): ImageBitmap? = LocalMascotHost.current.stills?.get(identity)

/** How much larger than its tile an agent's still is drawn, so the character fills the orb's square. */
const val AGENT_TILE_OVERSCALE: Float = MASCOT_TILE_OVERSCALE
