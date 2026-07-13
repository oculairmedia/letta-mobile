@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.letta.mobile.desktop.avatar.pet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.letta.mobile.avatar.core.AvatarActivity
import com.letta.mobile.avatar.core.AvatarCameraFraming
import com.letta.mobile.avatar.core.AvatarDirector
import com.letta.mobile.avatar.core.AvatarExpression
import com.letta.mobile.avatar.core.AvatarFormat
import com.letta.mobile.avatar.core.AvatarGesture
import com.letta.mobile.avatar.core.AvatarModel
import com.letta.mobile.avatar.catalog.AvatarCatalog
import com.letta.mobile.avatar.catalog.AvatarCatalogCodec
import com.letta.mobile.avatar.catalog.JsonFileAvatarCatalogStore
import com.letta.mobile.avatar.pipeline.resolveCatalogUri
import com.letta.mobile.avatar.rendererweb.AvatarWebHost
import com.letta.mobile.avatar.rendererweb.WebAvatarRuntime
import com.letta.mobile.desktop.avatar.defaultAvatarCatalogDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * P4 spike — the frameless pet-window surface host (PRD §4 P4, §5 B1).
 *
 * Proves the risky assumptions end to end on Windows:
 *  - CEF off-screen frames ([ComposeOsrBrowser]) composited with per-pixel
 *    alpha inside a transparent, undecorated, always-on-top Compose window;
 *  - zero focus steal (AWT non-focusable + WS_EX_NOACTIVATE), no Alt-Tab
 *    entry (WS_EX_TOOLWINDOW);
 *  - toggleable OS click-through (WS_EX_TRANSPARENT) with an auto-revert
 *    escape hatch;
 *  - drag-anywhere window moving while the avatar stays live.
 *
 * Run: `./gradlew :desktop:runPetSpike [-PpetVrm=path\to\model.vrm]`
 * Without an override it uses the first entry in the imported avatar catalog
 * (`~/.letta-mobile/avatars`); import a `.vrm` via the desktop Avatar library
 * first, or pass `-PpetVrm`. First run downloads the CEF bundle (~200MB) to
 * `~/.letta-mobile/jcef-bundle`.
 */
fun main(args: Array<String>) {
    val vrmPath = args.firstOrNull()?.let(Path::of) ?: resolveDefaultAvatar()
    if (vrmPath == null) {
        System.err.println(
            "[pet] No avatar found. Import a .vrm/.glb into the desktop Avatar library " +
                "(~/.letta-mobile/avatars) or pass -PpetVrm=path\\to\\model.vrm.",
        )
        return
    }
    application {
        val state = rememberWindowState(
            width = 380.dp,
            height = 560.dp,
            position = WindowPosition(Alignment.BottomEnd),
        )
        Window(
            onCloseRequest = ::exitApplication,
            state = state,
            title = "Letta Pet",
            undecorated = true,
            transparent = true,
            resizable = false,
            focusable = false,
            alwaysOnTop = true,
        ) {
            LaunchedEffect(Unit) { PetWindowStyles.applyBaseStyles(window) }
            PetSpikeContent(vrmPath = vrmPath, window = window, onClose = ::exitApplication)
        }
    }
}

/**
 * Resolve the avatar to show when no `-PpetVrm` override is supplied: the first
 * entry of the imported catalog, resolved to its on-disk asset. Returns null
 * when the catalog is empty or its asset can't be resolved — the imports write
 * `<slug>-<hash>.<ext>` under `assets/`, so there's no fixed filename to guess.
 */
private fun resolveDefaultAvatar(): Path? {
    val catalogDir = defaultAvatarCatalogDir()
    val catalog = AvatarCatalog(
        JsonFileAvatarCatalogStore(catalogDir.resolve(AvatarCatalogCodec.FILE_NAME)),
    )
    val model = runBlocking {
        runCatching {
            catalog.refresh()
            catalog.entries.value.firstOrNull()
        }.getOrNull()
    } ?: return null
    return runCatching { resolveCatalogUri(catalogDir, model.uri) }
        .getOrNull()
        ?.takeIf(Files::isRegularFile)
}

private data class Viewport(val widthDip: Int, val heightDip: Int, val scale: Double, val x: Int, val y: Int)

@Composable
private fun androidx.compose.ui.window.WindowScope.PetSpikeContent(
    vrmPath: Path,
    window: androidx.compose.ui.awt.ComposeWindow,
    onClose: () -> Unit,
) {
    val log: (String) -> Unit = remember { { println("[pet] $it") } }
    var status by remember { mutableStateOf("starting…") }
    var activity by remember { mutableStateOf(AvatarActivity.IDLE) }
    var clickThrough by remember { mutableStateOf(false) }
    var hovered by remember { mutableStateOf(false) }

    var cef by remember { mutableStateOf<PetCefHost?>(null) }
    val runtimeRef = remember { AtomicReference<WebAvatarRuntime?>(null) }
    val directorRef = remember { AtomicReference<AvatarDirector?>(null) }
    val hostRef = remember { AtomicReference<AvatarWebHost?>(null) }
    val viewport = remember { AtomicReference(Viewport(380, 560, 1.0, 0, 0)) }
    val density = LocalDensity.current

    // Boot: loopback host → chromium → renderer page → avatar → director.
    LaunchedEffect(Unit) {
        try {
            if (!Files.isRegularFile(vrmPath)) {
                status = "missing avatar: $vrmPath"
                return@LaunchedEffect
            }
            status = "starting avatar host…"
            val host = AvatarWebHost().started().also(hostRef::set)
            val runtime = WebAvatarRuntime(
                transport = host.transport,
                loadTimeoutMillis = 120_000,
                onRendererError = { log("renderer: $it") },
            ).also(runtimeRef::set)
            val director = AvatarDirector(runtime).also(directorRef::set)

            status = "booting chromium… (first run downloads ~200MB)"
            val startedCef = withContext(Dispatchers.IO) { PetCefHost.start(log) }
            cef = startedCef
            startedCef.open("${host.baseUrl}/pet.html")

            status = "loading avatar…"
            runtime.load(
                AvatarModel(
                    id = "pet-spike",
                    displayName = vrmPath.fileName.toString().substringBeforeLast('.'),
                    uri = host.assetUrl(vrmPath),
                    format = if (vrmPath.fileName.toString().endsWith(".vrm", ignoreCase = true)) {
                        AvatarFormat.VRM_1 // frontend detects VRM 0.x itself
                    } else {
                        AvatarFormat.GLB
                    },
                ),
            )
            runtime.setCameraFraming(AvatarCameraFraming.FULL_BODY)
            director.setActivity(AvatarActivity.IDLE)
            status = ""
            log("pet running: ${host.baseUrl}/pet.html")

            // 30fps behavior tick + viewport mirroring (window may be moved).
            val timeSource = TimeSource.Monotonic
            var lastMark = timeSource.markNow()
            while (isActive) {
                delay(33L)
                val delta = lastMark.elapsedNow().inWholeMicroseconds / 1_000_000f
                lastMark = timeSource.markNow()
                director.tick(delta)
                val v = viewport.get()
                startedCef.updateViewport(v.widthDip, v.heightDip, v.scale, v.x, v.y)
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            status = "failed: ${t.message}"
            log("boot failed: $t")
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { runtimeRef.get()?.dispose() }
            runCatching { hostRef.get()?.close() }
            runCatching { cef?.dispose() }
        }
    }

    // Click-through leaves us unable to receive the toggle-off click — auto
    // revert after 10s (the real escape hatch is a global hotkey, P6/M1).
    LaunchedEffect(clickThrough) {
        if (clickThrough) {
            delay(10_000)
            PetWindowStyles.setClickThrough(window, false)
            clickThrough = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false },
    ) {
        // Drag anywhere on the body = move the window (PRD B1).
        WindowDraggableArea(modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().onPetSizeChanged(density.density, window, viewport)) {
                cef?.frame?.value?.let { bitmap ->
                    androidx.compose.foundation.Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds,
                    )
                }
            }
        }

        // Status chip (boot progress / director state) — PRD B1's chip slot.
        val chipText = when {
            status.isNotEmpty() -> status
            clickThrough -> "click-through (auto-off 10s)"
            else -> activity.name.lowercase()
        }
        Chip(
            text = chipText,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
        )

        // Hover micro-row: cycle presence states, click-through, close.
        if (hovered && !clickThrough) {
            Row(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (target in listOf(
                    AvatarActivity.IDLE,
                    AvatarActivity.THINKING,
                    AvatarActivity.SPEAKING,
                )) {
                    Chip(
                        text = target.name.lowercase(),
                        highlighted = activity == target,
                    ) {
                        activity = target
                        directorRef.get()?.setActivity(target)
                    }
                }
                Chip(text = "sad!") {
                    directorRef.get()?.flashEmotion(AvatarExpression.Sad)
                }
                // Built-in standard gesture set (renderer authors these
                // procedurally for VRM humanoids): a one-shot wave and a
                // celebratory "party" wave-both-arms.
                Chip(text = "wave") {
                    runtimeRef.get()?.playGesture(AvatarGesture("wave"), fadeSeconds = 0.3f)
                }
                Chip(text = "party") {
                    runtimeRef.get()?.playGesture(AvatarGesture("celebrate"), fadeSeconds = 0.3f)
                }
                Chip(text = "ghost") {
                    PetWindowStyles.setClickThrough(window, true)
                    clickThrough = true
                }
                Chip(text = "✕") { onClose() }
            }
        }
    }
}

private fun Modifier.onPetSizeChanged(
    density: Float,
    window: androidx.compose.ui.awt.ComposeWindow,
    viewport: AtomicReference<Viewport>,
): Modifier = onSizeChanged { size ->
    val location = runCatching { window.locationOnScreen }.getOrNull()
    viewport.set(
        Viewport(
            widthDip = (size.width / density).toInt(),
            heightDip = (size.height / density).toInt(),
            scale = density.toDouble(),
            x = location?.x ?: 0,
            y = location?.y ?: 0,
        ),
    )
}

@Composable
private fun Chip(
    text: String,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val background = if (highlighted) Color(0xCC00BFA5) else Color(0xB31E1E24)
    Box(
        modifier = modifier
            .background(background, RoundedCornerShape(10.dp))
            .let { m -> onClick?.let { m.clickable(onClick = it) } ?: m }
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        BasicText(text = text, style = TextStyle(color = Color(0xFFE8E8EC), fontSize = 11.sp))
    }
}
