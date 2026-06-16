package com.letta.mobile.feature.chat

import androidx.compose.runtime.Composable
import com.letta.mobile.ui.common.SharedChatUiProbe

/**
 * Slice-1 verification: Android can consume shared composables from sharedLogic.
 * Remove this file in later slices.
 */
@Composable
internal fun AndroidSharedUiProbeReference() {
    // This proves Android can import and call SharedChatUiProbe from sharedLogic's jvmAndAndroid source set.
    // Not actually rendered anywhere; just ensures the symbol resolves at compile time.
    @Suppress("UNUSED_EXPRESSION")
    if (false) {
        SharedChatUiProbe("android-probe-verification")
    }
}
