package com.letta.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import com.letta.mobile.ui.theme.tokens.LocalLettaCustomColors

/** The theme's extra colour roles; defined beside the leaf tokens, see [com.letta.mobile.ui.theme.tokens.CustomColors]. */
typealias CustomColors = com.letta.mobile.ui.theme.tokens.CustomColors

/** The [CustomColors] in scope, provided by every Letta theme. */
val LocalCustomColors: ProvidableCompositionLocal<CustomColors> = LocalLettaCustomColors

@Suppress("UnusedReceiverParameter")
val MaterialTheme.customColors: CustomColors
    @Composable
    @ReadOnlyComposable
    get() = LocalCustomColors.current

val CustomColors.listItemColors: ListItemColors
    @Composable
    get() = ListItemDefaults.colors(containerColor = listItemContainerColor)
