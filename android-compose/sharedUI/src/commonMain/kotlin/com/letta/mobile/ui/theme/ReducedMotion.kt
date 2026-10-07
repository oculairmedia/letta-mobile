package com.letta.mobile.ui.theme

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Ambient reduced-motion preference for shared Compose UI. True snaps the shared chat page's
 * motion (row placement, disclosures, label swaps, composer morphs) and stills its loops.
 *
 * Providers: Android's shared chat page (feature-chat SharedChatPage, from the OS "Remove
 * animations" setting, letta-mobile-bglj6.1.19) and the desktop phone preview
 * (DesktopPhoneScreen). Anything outside them sees the default `false`.
 */
val LocalReducedMotion: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }
