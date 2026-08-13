package com.letta.mobile.desktop.qr

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.letta.mobile.desktop.DesktopDefaultButton
import com.letta.mobile.desktop.DesktopMaterialTheme
import com.letta.mobile.desktop.DesktopOutlinedButton
import com.letta.mobile.qr.QrRenderer
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image

/**
 * letta-mobile-nonza (sixv8.3): the desktop install / first-run pairing
 * surface.
 *
 * On first launch the screen mints a QR via the existing
 * `pair.invite.create` extension (see `reference/qr-pairing-protocol.md`
 * §4 + §5). The wire value is the `letta-qr-v1.<base64url-json>` payload
 * from the `qr_invite` response field — byte-identical to the value the
 * CLI's `pair` subcommand (sixv8.2) mints; the desktop is the renderer
 * only.
 *
 * Once a phone scans the QR and dials in, the pairing store updates and
 * the controller's [DesktopPairInviteController.refreshPeers] observer
 * surfaces the new peer. The screen then auto-navigates to the chat home
 * via [onPairingCompleted].
 */
@Composable
internal fun DesktopPairInstallScreen(
    controller: DesktopPairInviteController,
    onPairingCompleted: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    // Initial mint — fire and forget. The controller's `loading` flag gates
    // re-entry; tapping "regenerate" while loading is a no-op.
    LaunchedEffect(controller) {
        if (controller.wireValue == null && !controller.loading) {
            controller.mint()
        }
    }
    // Watch the pairing store. The store itself does not flow, so we poll
    // every 500 ms while the screen is mounted; when a new peer appears we
    // hand control back to the caller (chat home).
    LaunchedEffect(controller) {
        var lastSeen = controller.peers.size
        while (true) {
            controller.refreshPeers()
            if (controller.peers.size > lastSeen) {
                onPairingCompleted()
                return@LaunchedEffect
            }
            lastSeen = controller.peers.size
            delay(500L)
        }
    }
    DesktopMaterialTheme {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Welcome to Letta Desktop",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Pair your phone to start chatting from anywhere.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            QrCard(controller = controller)
            Spacer(modifier = Modifier.height(8.dp))
            InstructionalFooter(controller = controller)
            Spacer(modifier = Modifier.height(8.dp))
            Row(actions = controller, onCancel = onCancel)
        }
    }
}

/**
 * Renders the PNG produced by the controller. We decode the file into a
 * Compose [ImageBitmap] via Skiko (the same path the chat surface uses for
 * attached images) so a 512x512 QR shows up at its native resolution.
 */
@Composable
private fun QrCard(controller: DesktopPairInviteController) {
    Box(
        modifier = Modifier
            .size(360.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            controller.loading && controller.pngFile == null -> CircularProgressIndicator()
            controller.pngFile != null -> {
                val bitmap = rememberQrBitmap(controller.pngFile!!)
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "Pairing QR code",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Text(
                        text = "Failed to load rendered QR",
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            else -> Text(
                text = controller.error ?: "Preparing QR…",
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun InstructionalFooter(controller: DesktopPairInviteController) {
    val suggested = controller.suggestedName
    val expiresAtMs = controller.expiresAtMs
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "Open the Letta mobile app, tap \"Pair a desktop\", and point your camera at this code.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!suggested.isNullOrBlank()) {
            Text(
                text = "Suggested name: $suggested",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expiresAtMs != null) {
            val humanTime = remember(expiresAtMs) {
                Instant.ofEpochMilli(expiresAtMs)
                    .atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
            }
            Text(
                text = "Expires at $humanTime",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Row(
    actions: DesktopPairInviteController,
    onCancel: () -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DesktopDefaultButton(
            onClick = { actions.mint() },
            enabled = !actions.loading,
        ) {
            Icon(
                imageVector = Icons.Outlined.Refresh,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "Regenerate")
        }
        DesktopOutlinedButton(
            onClick = onCancel,
            enabled = !actions.loading,
        ) {
            Text(text = "Cancel")
        }
    }
}

/**
 * Decode a PNG file into a Compose [ImageBitmap] using Skiko (the same
 * Skia path the chat composer uses for attached images). This is the
 * Compose-Multiplatform JVM path — `painterResource` would require a
 * bundled resource, but we want the file on disk so the controller owns
 * the render lifecycle.
 */
@Composable
internal fun rememberQrBitmap(file: File): ImageBitmap? {
    var bitmap by remember(file.path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file.path) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap()
            }.getOrNull()
        }
    }
    return bitmap
}

/**
 * Pure (non-Composable) helper that loads a [File] into an [ImageBitmap]
 * synchronously. Tests use this — the composable wrapper above is the
 * production surface.
 */
internal fun qrBitmapFromFile(file: File): ImageBitmap? = runCatching {
    Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap()
}.getOrNull()
