package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import com.letta.mobile.ui.common.SharedChatUiProbe

/**
 * Slice-1 verification: desktop can consume shared composables from sharedLogic.
 * Remove this file in later slices.
 */
@Composable
internal fun DesktopSharedUiProbeReference() {
    // This proves desktop can import and call SharedChatUiProbe from sharedLogic's jvmAndAndroid source set.
    // Not actually rendered anywhere; just ensures the symbol resolves at compile time.
    @Suppress("UNUSED_EXPRESSION")
    if (false) {
        SharedChatUiProbe("desktop-probe-verification")
    }
}
