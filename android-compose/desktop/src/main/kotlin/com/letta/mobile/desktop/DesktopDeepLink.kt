package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.StateFlow

/** Routes each deep-link delivery; parsing and routing live in DesktopDeepLinkRouting.kt. */
@Composable
internal fun DesktopDeepLinkEffect(
    deepLinks: StateFlow<DesktopDeepLinkRequest?>,
    handlers: DesktopDeepLinkHandlers,
) {
    val pendingDeepLink by deepLinks.collectAsState()
    LaunchedEffect(pendingDeepLink) {
        handleDesktopDeepLink(pendingDeepLink?.uri?.let(::parseDesktopDeepLink), handlers)
    }
}
