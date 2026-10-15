package com.letta.mobile.desktop.plugin.view

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.ui.canvas.plugin.PluginViewHost
import com.letta.mobile.ui.canvas.plugin.PluginViewSession
import com.letta.mobile.ui.theme.LettaDimens
import org.cef.CefApp
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

/**
 * The desktop [PluginViewHost] (letta-mobile-s416w.14, letta-mobile-cdgpr.3): each live element is
 * a JCEF browser embedded through SwingPanel, opened on the process's one browser runtime. While
 * that runtime is first installed or started, the view shows a one-line notice with the progress;
 * a runtime that is not available here, a browser that cannot be opened, a page that fails to load
 * or never starts, and a renderer that dies all hand the element back to its fallback card through
 * `onFailure`. When the element leaves the board the bridge is torn down and the browser closed
 * as the host's work, which outlives the board ([rememberJcefPluginViewHost]).
 *
 * [T] is the runtime's type: `CefApp` in the app, a fake in tests.
 */
internal class JcefPluginViewHost<T : Any>(internal val views: JcefLiveViews<T>) : PluginViewHost {
    @Composable
    override fun View(session: PluginViewSession, modifier: Modifier, onFailure: (reason: String) -> Unit) {
        val failure by rememberUpdatedState(onFailure)
        LaunchedEffect(views) { views.runtime.ensureStarted() }
        val state by views.runtime.state.collectAsState()
        when (val runtime = state) {
            is BrowserRuntimeState.Ready -> LiveBrowser(runtime.runtime, session, modifier) { reason -> failure(reason) }
            is BrowserRuntimeState.Preparing -> PreparingNotice(PluginViewNotices.preparing(runtime), session, modifier)
            BrowserRuntimeState.Idle -> PreparingNotice(PluginViewNotices.STARTING, session, modifier)
            is BrowserRuntimeState.Unavailable -> LaunchedEffect(runtime) { failure(runtime.reason) }
        }
    }

    @Composable
    private fun LiveBrowser(app: T, session: PluginViewSession, modifier: Modifier, onFailure: (String) -> Unit) {
        // JCEF reports faults on its own threads; the board hears them on the UI thread.
        val slot = remember(app, session) { JcefViewSlot(views, app, session) { reason -> SwingUtilities.invokeLater { onFailure(reason) } } }
        val live = slot.live
        if (live == null) {
            LaunchedEffect(slot) { onFailure(slot.failure) }
            return
        }
        SwingPanel(background = MaterialTheme.colorScheme.surfaceContainer, factory = { live.component }, modifier = modifier)
    }
}

/** The desktop's live-view host for one window: its views' teardowns run in the window's scope, which outlives every board. */
@Composable
internal fun rememberJcefPluginViewHost(): JcefPluginViewHost<CefApp> {
    val scope = rememberCoroutineScope()
    return remember(scope) { JcefPluginViewHost(JcefLiveViews(DesktopPluginViews.runtime, PluginViewWork(scope), JcefPluginBrowser)) }
}

/**
 * Ties one [JcefLiveView] to the composition: opened (and started) when the view is first composed,
 * closed (teardown, then the browser) when it is forgotten or its composition abandoned. [live] is
 * null when the browser could not be opened, and [failure] says why.
 */
internal class JcefViewSlot<T : Any>(
    private val views: JcefLiveViews<T>,
    app: T,
    session: PluginViewSession,
    onFault: (String) -> Unit,
) : RememberObserver {
    private val opened: Result<JcefLiveView> = runCatching { views.open(app, session, onFault) }

    val live: JcefLiveView? get() = opened.getOrNull()

    val failure: String get() = BrowserRuntime.unavailableReason(opened.exceptionOrNull() ?: IllegalStateException())

    override fun onRemembered() = Unit

    override fun onForgotten() {
        live?.let { views.close(it, PluginViewTeardown.CLOSED) }
    }

    override fun onAbandoned() = onForgotten()
}

/** The one-line notice a live view shows while the browser is first prepared. */
@Composable
private fun PreparingNotice(notice: String, session: PluginViewSession, modifier: Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.padding(LettaDimens.Space.sm).testTag(PluginViewTestTags.notice(session.spec.elementId)),
            shape = RoundedCornerShape(LettaDimens.Radius.sm),
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        ) {
            Text(
                text = notice,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = LettaDimens.Space.sm, vertical = LettaDimens.Space.xs),
            )
        }
    }
}

/** The notices a live view shows while the browser is not ready yet. */
internal object PluginViewNotices {
    private const val PERCENT = 100

    const val STARTING: String = "Starting the web view"

    fun preparing(state: BrowserRuntimeState.Preparing): String {
        val percent = state.progress?.let { " ${(it * PERCENT).roundToInt()}%" }.orEmpty()
        return when (state.stage) {
            "downloading" -> "Downloading the web view$percent (first run)"
            "extracting", "install" -> "Installing the web view$percent (first run)"
            else -> STARTING + percent
        }
    }
}

/** Test tags of the desktop live view. */
internal object PluginViewTestTags {
    fun notice(elementId: String): String = "canvas-plugin-notice-$elementId"
}

/** The process's embedded browser: one per process (CEF initialises once), started only when a live view first shows. */
internal object DesktopPluginViews {
    val runtime: BrowserRuntime<CefApp> by lazy {
        val config = JcefConfig.fromSystem()
        BrowserRuntime(JcefAppStarter(config), disabledReason = config.disabledReason)
    }
}

/** Why a view was torn down, as `host.teardown { reason }` tells the page. */
internal object PluginViewTeardown {
    /** The element left the board, or the board was closed. */
    const val CLOSED: String = "closed"
}
