package com.letta.mobile.ui.pairing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import com.letta.mobile.ui.theme.tokens.LettaTextStyles

/** Where pairing UI is drawn, which decides whose surface and heading scale it uses. */
internal enum class PairingPlacement {
    /** A page of its own (the scanner, "Pair a device"): its own status surface and title scale. */
    PAGE,

    /** Inside a Settings card, which already supplies the surface: no nested surface, the card's heading scale. */
    SETTINGS_CARD,
}

/** The placement of the pairing UI below; a page unless a Settings card says otherwise. */
internal val LocalPairingPlacement = staticCompositionLocalOf { PairingPlacement.PAGE }

/** Whether the pairing UI below is drawn inside a Settings card. */
internal val inSettingsCard: Boolean
    @Composable
    @ReadOnlyComposable
    get() = LocalPairingPlacement.current == PairingPlacement.SETTINGS_CARD

/** A pairing heading: [pageStyle] on a page of its own, the Settings card heading inside a Settings card. */
@Composable
@ReadOnlyComposable
internal fun pairingHeading(pageStyle: TextStyle): TextStyle = if (inSettingsCard) LettaTextStyles.titleSemibold else pageStyle
