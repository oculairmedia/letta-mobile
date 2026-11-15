package com.letta.mobile.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import com.letta.mobile.ui.theme.tokens.LocalLettaReducedMotion

/**
 * The ambient reduced-motion preference; defined beside the leaf tokens, see
 * [com.letta.mobile.ui.theme.tokens.LocalLettaReducedMotion]. The same local under its theme name,
 * so call sites that read it through ui.theme need no change.
 */
val LocalReducedMotion: ProvidableCompositionLocal<Boolean> = LocalLettaReducedMotion

/** The ambient reduced-motion flag. Same name the Android designsystem used to own. */
@Composable
fun rememberReducedMotionEnabled(): Boolean = LocalReducedMotion.current
