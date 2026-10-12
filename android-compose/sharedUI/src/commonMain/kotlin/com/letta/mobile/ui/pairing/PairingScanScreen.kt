package com.letta.mobile.ui.pairing

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.letta.mobile.data.pairing.PairingState
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.shell.header.HeaderControls
import com.letta.mobile.ui.shell.header.HeaderNav
import com.letta.mobile.ui.shell.header.HeaderTitle
import com.letta.mobile.ui.shell.header.ShellHeader
import com.letta.mobile.ui.shell.scaffold.LettaPageScaffold
import com.letta.mobile.ui.theme.LettaDimens

/** Where the scanner's camera stands (letta-mobile-dpv7m.5). */
enum class CameraAccess {
    /** No camera on this platform (desktop, web): the paste field is the scanner. */
    UNAVAILABLE,

    /** Not granted yet: explain why before the OS prompt. */
    NEEDS_RATIONALE,

    /** The OS prompt is up. */
    REQUESTING,
    GRANTED,
    DENIED,
}

/** The scanner's flashlight, when the camera has one. */
class TorchControl(val on: Boolean, val onToggle: () -> Unit)

/**
 * The platform camera container's contract: everything platform-specific
 * (permission, the preview surface + analyzer, the torch) behind a small
 * value; the screen around it is shared.
 */
class ScanCamera(
    val access: CameraAccess,
    val onRequestAccess: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val torch: TorchControl? = null,
    /** The live preview; calls `onDecoded` for every decoded QR text. Only composed while [CameraAccess.GRANTED]. */
    val preview: @Composable (onDecoded: (String) -> Unit) -> Unit = {},
) {
    companion object {
        val Unavailable = ScanCamera(CameraAccess.UNAVAILABLE)
    }
}

/**
 * "Scan to connect" as a full page (letta-mobile-dpv7m.5): header with back,
 * then [PairingScanContent]. Android hosts it around its CameraX container.
 */
@Composable
fun PairingScanScreen(
    state: PairingState,
    camera: ScanCamera,
    actions: PairingScanActions,
    clock: () -> Long,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LettaPageScaffold(
        modifier = modifier,
        header = {
            ShellHeader(
                title = HeaderTitle.Text(PairingStrings.SCAN_TITLE),
                controls = HeaderControls(nav = HeaderNav.Back(onNavigateBack)),
            )
        },
    ) { padding ->
        PairingScanContent(
            state = state,
            camera = camera,
            actions = actions,
            nowMs = rememberTickingNow(clock, active = state is PairingState.ConfirmHost),
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()),
        )
    }
}

/**
 * The scanner body, shared by every platform: viewfinder (corner brackets,
 * torch, helper line) or the permission rationale / denied state, a
 * first-class "Paste link" path, and the status card (trust prompt, Verifying,
 * Success, Error). While a code is being handled the camera keeps showing but
 * its frames are ignored, so one code never triggers two prompts.
 */
@Composable
fun PairingScanContent(
    state: PairingState,
    camera: ScanCamera,
    actions: PairingScanActions,
    nowMs: Long,
    modifier: Modifier = Modifier,
) {
    var pasting by remember { mutableStateOf(false) }
    val pasteOnly = camera.access == CameraAccess.UNAVAILABLE
    Column(
        modifier = modifier.padding(LettaDimens.Space.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.lg),
    ) {
        if (!pasteOnly && !pasting) {
            CameraSection(camera, onDecoded = { raw -> if (state is PairingState.Idle) actions.onSubmit(raw) }, onPaste = { pasting = true })
        }
        if (pasteOnly || pasting) {
            PasteSection(pasteOnly = pasteOnly, enabled = state.acceptsInput(), onSubmit = actions.onSubmit, onUseCamera = { pasting = false })
        }
        PairingStatusCard(state = state, actions = actions, nowMs = nowMs)
    }
}

private fun PairingState.acceptsInput() = this is PairingState.Idle || this is PairingState.Failed

@Composable
private fun CameraSection(camera: ScanCamera, onDecoded: (String) -> Unit, onPaste: () -> Unit) {
    when (camera.access) {
        CameraAccess.GRANTED -> Viewfinder(camera, onDecoded)
        CameraAccess.DENIED -> CameraNotice(
            tag = PairingTags.CAMERA_DENIED,
            title = PairingStrings.CAMERA_DENIED_TITLE,
            body = PairingStrings.CAMERA_DENIED_BODY,
            actionLabel = PairingStrings.OPEN_SETTINGS,
            actionTag = PairingTags.OPEN_SETTINGS,
            onAction = camera.onOpenSettings,
        )
        CameraAccess.NEEDS_RATIONALE, CameraAccess.REQUESTING -> CameraNotice(
            tag = PairingTags.CAMERA_RATIONALE,
            title = PairingStrings.CAMERA_RATIONALE_TITLE,
            body = PairingStrings.CAMERA_RATIONALE_BODY,
            actionLabel = PairingStrings.CAMERA_ALLOW,
            actionTag = PairingTags.CAMERA_ALLOW,
            onAction = camera.onRequestAccess,
            actionEnabled = camera.access == CameraAccess.NEEDS_RATIONALE,
        )
        CameraAccess.UNAVAILABLE -> Unit
    }
    val pasteLabel = if (camera.access == CameraAccess.GRANTED) PairingStrings.PASTE_LINK else PairingStrings.PASTE_INSTEAD
    TextButton(onClick = onPaste, modifier = Modifier.testTag(PairingTags.PASTE_TOGGLE)) { Text(pasteLabel) }
}

@Composable
private fun Viewfinder(camera: ScanCamera, onDecoded: (String) -> Unit) {
    Box(
        modifier = Modifier
            .widthIn(max = LettaDimens.Pane.qrCodeMaxWidth)
            .fillMaxWidth()
            .aspectRatio(1f)
            .testTag(PairingTags.CAMERA_AREA),
    ) {
        camera.preview(onDecoded)
        ScanBrackets(Modifier.align(Alignment.Center).size(LettaDimens.Pane.scanFrameSize))
        camera.torch?.let { torch ->
            FilledTonalIconToggleButton(
                checked = torch.on,
                onCheckedChange = { torch.onToggle() },
                modifier = Modifier.align(Alignment.TopEnd).padding(LettaDimens.Space.sm).testTag(PairingTags.TORCH),
            ) {
                Icon(
                    if (torch.on) LettaIcons.Flashlight else LettaIcons.FlashlightOff,
                    contentDescription = if (torch.on) PairingStrings.TORCH_ON else PairingStrings.TORCH_OFF,
                )
            }
        }
    }
    Text(
        PairingStrings.CAMERA_READY,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** Four corner brackets marking where the code should sit. */
@Composable
private fun ScanBrackets(modifier: Modifier) {
    val stroke = LettaDimens.Pane.scanBracketStroke
    val length = LettaDimens.Pane.scanBracketLength
    Canvas(modifier) {
        val w = stroke.toPx()
        val l = length.toPx()
        val corners = listOf(
            Offset(0f, 0f) to Offset(1f, 1f),
            Offset(size.width, 0f) to Offset(-1f, 1f),
            Offset(0f, size.height) to Offset(1f, -1f),
            Offset(size.width, size.height) to Offset(-1f, -1f),
        )
        corners.forEach { (corner, dir) ->
            drawLine(Color.White, corner, corner + Offset(dir.x * l, 0f), strokeWidth = w, cap = StrokeCap.Round)
            drawLine(Color.White, corner, corner + Offset(0f, dir.y * l), strokeWidth = w, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun CameraNotice(
    tag: String,
    title: String,
    body: String,
    actionLabel: String,
    actionTag: String,
    onAction: () -> Unit,
    actionEnabled: Boolean = true,
) {
    Surface(shape = MaterialTheme.shapes.large, tonalElevation = LettaDimens.Space.hair, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Column(
            modifier = Modifier.padding(LettaDimens.Space.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
        ) {
            Icon(LettaIcons.Camera, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(body, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onAction, enabled = actionEnabled, modifier = Modifier.testTag(actionTag)) { Text(actionLabel) }
        }
    }
}

@Composable
private fun PasteSection(pasteOnly: Boolean, enabled: Boolean, onSubmit: (String) -> Unit, onUseCamera: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
        Text(PairingStrings.PASTE_ONLY_HINT, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PairCodeEntry(onSubmit = onSubmit, enabled = enabled)
        if (!pasteOnly) {
            TextButton(onClick = onUseCamera, modifier = Modifier.testTag(PairingTags.PASTE_TOGGLE)) {
                Text(PairingStrings.SCAN_WITH_CAMERA)
            }
        }
    }
}
