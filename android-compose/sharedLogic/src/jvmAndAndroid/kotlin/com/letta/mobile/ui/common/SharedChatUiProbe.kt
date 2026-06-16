package com.letta.mobile.ui.common

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * Slice-1 probe: proves commonMain can host Compose-Multiplatform chat UI.
 * Remove/replace in later slices.
 */
@Composable
fun SharedChatUiProbe(label: String) {
    Text(label)
}
