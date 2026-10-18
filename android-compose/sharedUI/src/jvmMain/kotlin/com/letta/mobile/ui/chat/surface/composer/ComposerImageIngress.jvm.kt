package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Desktop's window installs AWT clipboard paste and file drop for the whole page (DesktopImageIngress). */
internal actual val platformComposerImageIngress: (@Composable (ComposerImageAttacher) -> Modifier)? = null
