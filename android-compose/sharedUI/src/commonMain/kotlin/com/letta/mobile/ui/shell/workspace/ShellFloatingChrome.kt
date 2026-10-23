package com.letta.mobile.ui.shell.workspace

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import com.letta.mobile.ui.chat.surface.ChatSurfacePlatform
import com.letta.mobile.ui.shell.header.ShellHeaderDefaults

/**
 * The host's chat platform with the workspace's floating chrome
 * ([com.letta.mobile.ui.shell.LocalShellFloatingChromeInset]) folded into its top chrome inset, so
 * the timeline rests its rows and holds its sticky prompt below the floating controls.
 */
fun ChatSurfacePlatform.underFloatingChrome(inset: Dp): ChatSurfacePlatform =
    if (inset > topChromeInset) copy(topChromeInset = inset) else this

/** The compact inline window's floating header: the minimal header, below the status bar [statusBar]. */
internal class FloatingChrome(val header: ShellWorkspaceHeaderState, val statusBar: Dp) {
    /** The band it floats over: the status bar and the phone app bar's height. */
    val inset: Dp get() = statusBar + ShellHeaderDefaults.TouchMinHeight
}

/**
 * The minimal header floating over the page: transparent, so the page shows through and scrolls
 * under it, and with no pointer handling of its own, so only its controls take touches and the
 * rest of the band reaches the page.
 */
@Composable
internal fun ShellFloatingHeader(chrome: FloatingChrome) {
    ShellWorkspaceHeader(
        chrome.header,
        Modifier
            .fillMaxWidth()
            .padding(top = chrome.statusBar)
            .height(ShellHeaderDefaults.TouchMinHeight)
            .testTag(LettaShellWorkspaceTags.FLOATING_HEADER),
    )
}
