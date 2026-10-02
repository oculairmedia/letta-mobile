package com.letta.mobile.ui.text

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/** A desktop right-click menu is drawn by the container that owns it; it keeps [density]. */
@Composable
internal actual fun KeepMenusAtDensity(density: Density, content: @Composable () -> Unit) {
    val inner = LocalContextMenuRepresentation.current
    val atDensity = remember(inner, density) { MenuAtDensity(inner, density) }
    CompositionLocalProvider(LocalContextMenuRepresentation provides atDensity, content = content)
}

private class MenuAtDensity(
    private val inner: ContextMenuRepresentation,
    private val density: Density,
) : ContextMenuRepresentation {
    @Composable
    override fun Representation(state: ContextMenuState, items: () -> List<ContextMenuItem>) {
        CompositionLocalProvider(LocalDensity provides density) { inner.Representation(state, items) }
    }
}
