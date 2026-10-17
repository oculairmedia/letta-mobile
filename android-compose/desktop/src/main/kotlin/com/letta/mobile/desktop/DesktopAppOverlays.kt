package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import com.letta.mobile.data.shell.ShellOverlays
import com.letta.mobile.desktop.chat.DesktopModelControlHost
import com.letta.mobile.desktop.chat.DesktopModelControlOverlays
import com.letta.mobile.desktop.chat.DesktopModelSurfaces
import com.letta.mobile.ui.host.LettaConfirmPane
import com.letta.mobile.ui.host.LettaConfirmRequest
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceOverlayActions
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceOverlayData
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceOverlays

/** The model surfaces' flags are the shared overlay stack's (letta-mobile-ckmub.17.3). */
private class SharedModelSurfaces(overlays: ShellOverlays) : DesktopModelSurfaces {
    override var modelPicker by overlays::modelPicker
    override var modelsEditor by overlays::modelsEditor
}

@Immutable
internal data class DesktopOverlayData(
    val composerModelLabel: String,
    /** The workspace's own overlays' inputs (the palette, New conversation, the drop hint). */
    val workspace: ShellWorkspaceOverlayData,
)

@Immutable
internal data class DesktopOverlayActions(
    /** The model picker's and Models sheet's host (letta-mobile-w4q4p.6.1). */
    val modelControl: DesktopModelControlHost,
    val onNavigate: (DesktopDestination) -> Unit,
    val onIrohIdentityReset: () -> Unit,
    val workspace: ShellWorkspaceOverlayActions,
)

/**
 * The desktop's overlays over the shared workspace's ([ShellWorkspaceOverlays]): the model picker
 * (over the desktop chat controller's models) and the Iroh identity reset confirmation.
 */
@Composable
internal fun DesktopAppOverlays(
    visibility: ShellOverlays,
    data: DesktopOverlayData,
    actions: DesktopOverlayActions,
) {
    DesktopModelControlOverlays(
        surfaces = remember(visibility) { SharedModelSurfaces(visibility) },
        host = actions.modelControl,
        selectedValue = data.composerModelLabel,
        onOpenProviders = { actions.onNavigate(DesktopDestination.Providers) },
    )
    ShellWorkspaceOverlays(visibility, data.workspace, actions.workspace)
    if (visibility.irohResetConfirm) {
        LettaConfirmPane(
            request = LettaConfirmRequest(
                title = "Reset Iroh identity?",
                message = "This mints a new NodeId and breaks existing device pairings until you re-pair.",
                confirmLabel = "Reset identity",
            ),
            onConfirm = {
                visibility.irohResetConfirm = false
                actions.onIrohIdentityReset()
            },
            onDismiss = { visibility.irohResetConfirm = false },
        )
    }
}
