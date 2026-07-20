package com.letta.mobile.desktop.avatar

import com.letta.mobile.avatar.core.AvatarActivity
import com.letta.mobile.avatar.core.AvatarDirector
import com.letta.mobile.avatar.core.AvatarExpression
import com.letta.mobile.avatar.core.AvatarFormat
import com.letta.mobile.avatar.core.AvatarModel
import com.letta.mobile.avatar.rendererweb.AvatarWebHost
import com.letta.mobile.avatar.rendererweb.WebAvatarRuntime
import java.awt.Desktop
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

import kotlin.time.Duration.Companion.milliseconds
/**
 * Desktop "pet mode" v1: hosts the avatar-web renderer on the loopback
 * [AvatarWebHost], opens it in the default browser, and drives it with an
 * [AvatarDirector] fed by the app's agent-presence state. The embedded
 * (JCEF) window and the OS pet-window contract (topmost/click-through)
 * come later behind the same pieces — only the transport attachment and
 * the window opening change.
 */
class DesktopAvatarCompanion(
    private val scope: CoroutineScope,
    private val openPage: (String) -> Unit = ::openInDefaultBrowser,
    private val onLog: (String) -> Unit = {},
) {
    sealed interface State {
        data object Stopped : State
        data object Starting : State
        data class Running(val pageUrl: String, val modelName: String) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Stopped)
    val state: StateFlow<State> = _state.asStateFlow()

    private var host: AvatarWebHost? = null
    private var runtime: WebAvatarRuntime? = null
    private var director: AvatarDirector? = null
    private var ticker: Job? = null
    private var startJob: Job? = null
    private var lastActivity = AvatarActivity.IDLE

    /** Launch the companion for the avatar at [vrmPath]. No-op while active. */
    fun start(vrmPath: Path) {
        if (_state.value is State.Starting || _state.value is State.Running) return
        _state.value = State.Starting
        startJob = scope.launch {
            try {
                check(Files.isRegularFile(vrmPath)) { "Avatar file not found: $vrmPath" }
                val startedHost = AvatarWebHost().started()
                host = startedHost
                val startedRuntime = WebAvatarRuntime(
                    transport = startedHost.transport,
                    // Generous: the user's browser has to open and load the page.
                    loadTimeoutMillis = 60_000,
                    onRendererError = { onLog("avatar renderer: $it") },
                )
                runtime = startedRuntime
                director = AvatarDirector(startedRuntime)

                openPage(startedHost.pageUrl)

                val fileName = vrmPath.fileName.toString()
                startedRuntime.load(
                    AvatarModel(
                        id = "companion",
                        displayName = fileName.substringBeforeLast('.'),
                        uri = startedHost.assetUrl(vrmPath),
                        format = if (fileName.endsWith(".vrm", ignoreCase = true)) {
                            AvatarFormat.VRM_1 // frontend detects VRM 0.x itself
                        } else {
                            AvatarFormat.GLB
                        },
                    ),
                )

                director?.setActivity(lastActivity)
                startTicker()
                _state.value = State.Running(startedHost.pageUrl, fileName)
                onLog("avatar companion running at ${startedHost.pageUrl}")
            } catch (cancelled: CancellationException) {
                teardown()
                throw cancelled
            } catch (t: Throwable) {
                teardown()
                _state.value = State.Failed(t.message ?: "Could not start the avatar companion")
                onLog("avatar companion failed: ${t.message}")
            }
        }
    }

    /** Agent presence → avatar behavior (safe to call in any state). */
    fun setActivity(activity: AvatarActivity) {
        lastActivity = activity
        director?.setActivity(activity)
    }

    /** Brief sad flash for surfaced errors. */
    fun flashError() {
        director?.flashEmotion(AvatarExpression.Sad)
    }

    fun stop() {
        startJob?.cancel()
        startJob = null
        teardown()
        _state.value = State.Stopped
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            val timeSource = TimeSource.Monotonic
            var lastMark = timeSource.markNow()
            while (isActive) {
                delay(TICK_MILLIS.milliseconds)
                val delta = lastMark.elapsedNow().inWholeMicroseconds / 1_000_000f
                lastMark = timeSource.markNow()
                director?.tick(delta)
            }
        }
    }

    private fun teardown() {
        ticker?.cancel()
        ticker = null
        director = null
        runtime?.dispose() // also closes the transport
        runtime = null
        host?.close()
        host = null
    }

    private companion object {
        const val TICK_MILLIS = 33L // ~30 fps behavior updates
    }
}

private fun openInDefaultBrowser(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
        }
    }
}
