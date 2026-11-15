package com.letta.mobile.ui.canvas

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight

/** A note's title in the opened editor: `headlineSmall` at medium weight, a heading that does not shout over the body. */
internal val NoteTitleTextStyle: TextStyle
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Medium)
