package com.letta.mobile.desktop.components

import androidx.compose.runtime.Composable
import com.letta.mobile.ui.components.LettaChipTab

/**
 * A small, tokenized filter/segment chip used in desktop headers. The catalog cards, pills and
 * grids the Skills / Channels pages draw live in sharedUI (LettaCatalog.kt).
 */
@Composable
internal fun DesktopChipTab(text: String, active: Boolean, onClick: () -> Unit) =
    LettaChipTab(text = text, active = active, onClick = onClick)
