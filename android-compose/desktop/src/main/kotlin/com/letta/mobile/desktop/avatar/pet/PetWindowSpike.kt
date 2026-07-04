@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.letta.mobile.avatar.core.AvatarAnimationFormat
import com.letta.mobile.avatar.core.AvatarAnimationSource
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
import kotlinx.coroutines.launch
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
    // Per-pixel transparency must survive GPU/display events: on the
    // accelerated (Direct3D) path a driver overlay toast or display change
    // forces a swapchain recreate that can come back WITHOUT composition
    // alpha — the window turns opaque black (observed live with an NVIDIA
    // overlay notification). The pet only composites a CPU bitmap (the CEF
    // frame) into a small window, so software presentation is both robust
    // (layered-window path, unaffected by GPU resets) and cheap.
    if (System.getProperty("skiko.renderApi") == null) {
        System.setProperty("skiko.renderApi", "SOFTWARE_FAST")
    }
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

        // Real Letta chat, wired to the user's actual backend (PRD §5 B1). Lives
        // at the application scope so BOTH the pet window and the focusable reply
        // popup share one session. Started once; closed on app exit.
        val appScope = rememberCoroutineScope()
        val chatSession = remember { PetChatSession(scope = appScope, log = { println("[pet] $it") }) }
        val chatState by chatSession.state.collectAsState()
        LaunchedEffect(chatSession) { chatSession.start() }
        DisposableEffect(chatSession) { onDispose { chatSession.close() } }

        // Reply-popup toggle + the pet window's live on-screen bounds (so the
        // popup can anchor below it, clamped to screen).
        var chatOpen by remember { mutableStateOf(false) }
        var petBounds by remember { mutableStateOf(Triple(0, 0, 560)) }

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
            PetSpikeContent(
                vrmPath = vrmPath,
                window = window,
                onClose = ::exitApplication,
                chatState = chatState,
                chatOpen = chatOpen,
                onToggleChat = { chatOpen = !chatOpen },
                onPetOriginChanged = { x, y, h -> petBounds = Triple(x, y, h) },
            )
        }

        if (chatOpen) {
            PetReplyWindow(
                chat = chatState,
                anchorX = petBounds.first,
                anchorY = petBounds.second,
                anchorHeight = petBounds.third,
                onSend = { text -> chatSession.send(text) },
                onClose = { chatOpen = false },
            )
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

/**
 * Scan the drop-in animation folder (`~/.letta-mobile/avatars/animations`) for
 * user-provided `.vrma`/`.fbx`/`.glb` files and register each with the loopback
 * [host] so the renderer can fetch it. The folder is created if missing. Each id
 * is the filename stem lowercased. Ill-formed or duplicate-id files are skipped
 * with a log line rather than aborting the pet boot.
 *
 * `.glb` here is always treated as an *animation* (a Mixamo-compatible clip, e.g.
 * the Ready Player Me library), even though avatar *models* are also `.glb`:
 * this directory is scanned exclusively for animations, so the extension alone
 * is unambiguous in this context. (Models live in the catalog `assets/` dir, not
 * here.) License note: the RPM library is proprietary (RPM OÜ) and may NOT be
 * redistributed/bundled — the user drops the files here himself.
 */
private fun scanDropInAnimations(host: AvatarWebHost, log: (String) -> Unit): List<AvatarAnimationSource> {
    val dir = defaultAvatarCatalogDir().resolve("animations")
    runCatching { Files.createDirectories(dir) }
        .onFailure { log("could not create animation dir $dir: ${it.message}"); return emptyList() }

    val sources = mutableListOf<AvatarAnimationSource>()
    val seenIds = mutableSetOf<String>()
    runCatching {
        Files.list(dir).use { stream ->
            stream.filter(Files::isRegularFile).sorted().forEach { path ->
                val name = path.fileName.toString()
                val format = when {
                    name.endsWith(".vrma", ignoreCase = true) -> AvatarAnimationFormat.VRMA
                    name.endsWith(".fbx", ignoreCase = true) -> AvatarAnimationFormat.FBX
                    // .glb in the animation dir is always an animation clip (see
                    // the doc comment) — models are .glb too but live elsewhere.
                    name.endsWith(".glb", ignoreCase = true) -> AvatarAnimationFormat.GLB
                    else -> return@forEach
                }
                val id = name.substringBeforeLast('.').lowercase()
                if (!seenIds.add(id)) {
                    log("animation id '$id' already registered — skipping duplicate $name")
                    return@forEach
                }
                sources += AvatarAnimationSource(id = id, uri = host.assetUrl(path), format = format)
            }
        }
    }.onFailure { log("failed to scan animation dir $dir: ${it.message}") }

    if (sources.isEmpty()) {
        log("animations: none — drop .vrma/.fbx/.glb into $dir")
    } else {
        log("animations: ${sources.joinToString(", ") { it.id }}")
    }
    return sources
}

private data class Viewport(val widthDip: Int, val heightDip: Int, val scale: Double, val x: Int, val y: Int)

@Composable
private fun androidx.compose.ui.window.WindowScope.PetSpikeContent(
    vrmPath: Path,
    window: androidx.compose.ui.awt.ComposeWindow,
    onClose: () -> Unit,
    chatState: PetChatUiState,
    chatOpen: Boolean,
    onToggleChat: () -> Unit,
    onPetOriginChanged: (x: Int, y: Int, height: Int) -> Unit,
) {
    val log: (String) -> Unit = remember { { println("[pet] $it") } }
    var status by remember { mutableStateOf("starting…") }
    var activity by remember { mutableStateOf(AvatarActivity.IDLE) }
    var clickThrough by remember { mutableStateOf(false) }
    var hovered by remember { mutableStateOf(false) }
    var importedAnimIds by remember { mutableStateOf(emptyList<String>()) }
    // Bubble text shown right now; held ~4s after the stream ends (§6 SPEAKING).
    var bubbleText by remember { mutableStateOf("") }

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

            // Drop-in animation folder: user .vrma/.fbx files retargeted onto
            // the avatar at load. Scanned once at boot; ids are the filename
            // stem lowercased. Registered via the loopback host so the renderer
            // can fetch them.
            val animations = scanDropInAnimations(host, log)
            importedAnimIds = animations.map { it.id }

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
                animations = animations,
            )
            runtime.setCameraFraming(AvatarCameraFraming.FULL_BODY)
            director.setActivity(AvatarActivity.IDLE)
            status = ""
            log("pet running: ${host.baseUrl}/pet.html")

            // 30fps behavior tick + viewport mirroring (window may be moved).
            val timeSource = TimeSource.Monotonic
            var lastMark = timeSource.markNow()
            var lastOrigin = Int.MIN_VALUE to Int.MIN_VALUE
            while (isActive) {
                delay(33L)
                val delta = lastMark.elapsedNow().inWholeMicroseconds / 1_000_000f
                lastMark = timeSource.markNow()
                director.tick(delta)
                val v = viewport.get()
                startedCef.updateViewport(v.widthDip, v.heightDip, v.scale, v.x, v.y)
                // Report the window's live on-screen origin (it can be dragged)
                // so the reply popup can re-anchor to it. Only on change.
                val origin = runCatching { window.locationOnScreen }.getOrNull()
                if (origin != null && (origin.x to origin.y) != lastOrigin) {
                    lastOrigin = origin.x to origin.y
                    onPetOriginChanged(origin.x, origin.y, window.height)
                }
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

    // Live chat drives the director: THINKING while the agent works, SPEAKING
    // while the reply streams, a green SUCCESS flash + IDLE when it settles, and
    // a sad flash on error (PRD §5 B1 presence wiring). This OVERRIDES the manual
    // hover chips whenever a turn is in flight; when chat is idle the manual
    // selection (tracked in [activity]) is restored so the demo chips still work.
    var prevPhase by remember { mutableStateOf(PetChatPhase.CONNECTING) }
    LaunchedEffect(chatState.phase) {
        val director = directorRef.get()
        when (chatState.phase) {
            PetChatPhase.THINKING -> director?.setActivity(AvatarActivity.THINKING)
            PetChatPhase.REPLYING -> director?.setActivity(AvatarActivity.SPEAKING)
            PetChatPhase.ERROR -> {
                director?.setActivity(AvatarActivity.ERROR)
                director?.flashEmotion(AvatarExpression.Sad)
            }
            PetChatPhase.IDLE, PetChatPhase.CONNECTING -> {
                // Green flash on a completed reply, then hand presence back to
                // whatever the manual chips last selected.
                if (prevPhase == PetChatPhase.REPLYING) director?.notifyTaskSucceeded()
                director?.setActivity(activity)
            }
        }
        prevPhase = chatState.phase
    }

    // Speech bubble: mirror the streamed reply while REPLYING, then hold the
    // final text ~4s after the stream ends before clearing (§6 SPEAKING auto-hide).
    LaunchedEffect(chatState.bubbleText, chatState.phase) {
        if (chatState.bubbleText.isNotBlank()) {
            bubbleText = chatState.bubbleText
        } else if (bubbleText.isNotBlank() && chatState.phase != PetChatPhase.REPLYING) {
            delay(4_000)
            bubbleText = ""
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
                        // The frame arrives PET_RENDER_SUPERSAMPLE larger than the
                        // window; a high-quality downscale is the AA.
                        filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                    )
                }
            }
        }

        // Speech bubble: streams the assistant reply inside the pet window
        // (PRD §5 B1, §6 SPEAKING). Non-interactive v1; auto-hidden by the
        // effect above ~4s after the stream ends. Sits below the hover row.
        PetSpeechBubble(
            text = bubbleText,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 40.dp, start = 12.dp, end = 12.dp),
        )

        // Status chip (boot progress / chat progress / director state) — PRD B1's
        // chip slot. Once the avatar has booted, prefer the one-line chat
        // progress ("thinking…"/"replying…"/agent name) — Codex-pets pattern.
        val chipText = when {
            status.isNotEmpty() -> status
            clickThrough -> "click-through (auto-off 10s)"
            else -> chatState.errorMessage ?: chatState.statusLine
        }
        Chip(
            text = chipText,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
        )

        // Hover micro-row: chat, cycle presence states, click-through, close.
        // FlowRow: with imported animation chips the set no longer fits one
        // line in a 380dp window — wrap instead of clipping.
        if (hovered && !clickThrough) {
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp, start = 8.dp, end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // New "chat" chip: toggles the focusable reply popup (PRD §5 B1).
                Chip(text = "chat", highlighted = chatOpen) { onToggleChat() }
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
                // One chip per drop-in animation (~/.letta-mobile/avatars/animations).
                for (animId in importedAnimIds) {
                    Chip(text = animId) {
                        runtimeRef.get()?.playGesture(AvatarGesture(animId), fadeSeconds = 0.3f)
                    }
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

// Host-side supersampling factor: reported to CEF as extra device scale, so
// the whole page (canvas included) rasterizes oversized and the Compose draw
// downscales it with FilterQuality.High — that downscale IS the antialiasing.
// MSAA inside CEF's off-screen GL context is unreliable, and inflating the
// WebGL pixel ratio page-side changed the canvas compositing path; scaling at
// the CEF layer keeps the exact BGRA alpha path that is known to composite.
private const val PET_RENDER_SUPERSAMPLE = 2.0

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
            scale = density.toDouble() * PET_RENDER_SUPERSAMPLE,
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
