package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.MutableStateFlow

/** Routes each deep-link delivery; parsing and routing live in DesktopDeepLinkRouting.kt. */
@Composable
internal fun DesktopDeepLinkEffect(
    deepLinks: MutableStateFlow<DesktopDeepLinkRequest?>,
    handlers: DesktopDeepLinkHandlers,
) {
    val pendingDeepLink by deepLinks.collectAsState()
    LaunchedEffect(pendingDeepLink) {
        val request = pendingDeepLink ?: return@LaunchedEffect
        if (deepLinks.compareAndSet(request, null)) handleDesktopDeepLink(parseDesktopDeepLink(request.uri), handlers)
    }
}
