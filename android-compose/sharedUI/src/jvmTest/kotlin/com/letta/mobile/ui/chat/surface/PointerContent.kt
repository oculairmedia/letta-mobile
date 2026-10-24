@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.ProvideLettaHost

/**
 * Sets [content] under a pointer host: the wide desktop window's idiom (the composer card, the
 * docked panel, hover actions). Without it a test draws Touch, the default host's idiom.
 */
internal fun ComposeUiTest.setPointerContent(content: @Composable () -> Unit) {
    setContent { ProvideLettaHost(LettaHost.Pointer, content) }
}
