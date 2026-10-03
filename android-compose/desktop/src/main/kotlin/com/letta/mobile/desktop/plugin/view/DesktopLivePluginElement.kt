package com.letta.mobile.desktop.plugin.view

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Puzzle
import com.letta.mobile.data.plugin.view.ViewElement
import com.letta.mobile.ui.canvas.plugin.LocalPluginElementRenderers
import com.letta.mobile.ui.canvas.plugin.PluginElementChrome
import com.letta.mobile.ui.canvas.plugin.PluginElementRenderers
import com.letta.mobile.ui.canvas.plugin.PluginElementView
import com.letta.mobile.ui.canvas.plugin.PluginFallbackCard
import com.letta.mobile.ui.theme.LettaDimens
import org.cef.CefApp
import javax.swing.SwingUtilities

/**
 * What the desktop shell binds for plugin views: the element type prefixes that may render live
 * (`ext:<pluginId>/`, from the plugins enabled on the host) and where their views come from.
 * [None] registers nothing, so every element is its card.
 */
class DesktopPluginBindings(
    val livePrefixes: Set<String>,
    val source: PluginLiveViewSource,
) {
    /** [base] with the live renderer registered for every prefix in [livePrefixes]. */
    internal fun registerOn(base: PluginElementRenderers, host: DesktopPluginViewHost): PluginElementRenderers =
        livePrefixes.fold(base) { renderers, prefix ->
            renderers.register(prefix) { view, chrome -> DesktopLivePluginElement(view, chrome, source, host) }
        }

    companion object {
        val None: DesktopPluginBindings = DesktopPluginBindings(emptySet(), PluginLiveViewSource.None)
    }
}

/** [content] with the board drawing [bindings]' plugin elements live through JCEF (letta-mobile-s416w.14). */
@Composable
fun ProvideDesktopPluginViews(bindings: DesktopPluginBindings, content: @Composable () -> Unit) {
    ProvideDesktopPluginViews(bindings, DesktopPluginViews.host, content)
}

@Composable
internal fun ProvideDesktopPluginViews(bindings: DesktopPluginBindings, host: DesktopPluginViewHost, content: @Composable () -> Unit) {
    val base = LocalPluginElementRenderers.current
    val renderers = remember(base, bindings, host) { bindings.registerOn(base, host) }
    CompositionLocalProvider(LocalPluginElementRenderers provides renderers, content = content)
}

/**
 * One plugin element on a desktop board: its live page when it has a view and the browser is
 * ready; its card while the browser is first prepared (with the progress) or when it is not
 * available here (with the reason); its card with the fault badge, through [PluginElementChrome.fail],
 * when the page cannot be opened or fails to load.
 */
@Composable
internal fun DesktopLivePluginElement(
    view: PluginElementView,
    chrome: PluginElementChrome,
    source: PluginLiveViewSource,
    host: DesktopPluginViewHost,
) {
    val element = view.element
    val canvasId = view.canvasId
    val live = remember(element.id, element.type, canvasId, source) { canvasId?.let { source.liveViewOf(element, it) } }
    if (live == null) {
        PluginFallbackCard(view, chrome)
        return
    }
    LaunchedEffect(host) { host.runtime.ensureStarted() }
    val state by host.runtime.state.collectAsState()
    when (val runtime = state) {
        is BrowserRuntimeState.Ready -> LivePluginPage(view, chrome, LivePage(live, runtime.runtime, host))
        is BrowserRuntimeState.Preparing -> PluginCardWithNotice(view, chrome, PluginViewNotices.preparing(runtime))
        is BrowserRuntimeState.Unavailable -> PluginCardWithNotice(view, chrome, runtime.reason)
        BrowserRuntimeState.Idle -> PluginFallbackCard(view, chrome)
    }
}

/** A live view about to open: what it shows, on which browser, through which host. */
private class LivePage(val live: PluginLiveView, val app: CefApp, val host: DesktopPluginViewHost)

@Composable
private fun LivePluginPage(view: PluginElementView, chrome: PluginElementChrome, page: LivePage) {
    val fail = rememberUpdatedState(chrome.fail)
    val opened = remember(page.live, page.app) {
        runCatching { page.host.open(page.app, page.live) { reason -> SwingUtilities.invokeLater { fail.value(reason) } } }
    }
    val session = opened.getOrNull()
    if (session == null) {
        LaunchedEffect(opened) { chrome.fail(BrowserRuntime.unavailableReason(opened.exceptionOrNull() ?: IllegalStateException())) }
        PluginFallbackCard(view, chrome)
        return
    }
    DisposableEffect(session) { onDispose { page.host.close(session, PluginViewTeardown.CLOSED) } }
    LaunchedEffect(session, view.element) { session.elementChanged(ViewElement.of(view.element)) }
    Column(modifier = Modifier.fillMaxSize().testTag(PluginViewTestTags.live(view.element.id))) {
        LiveViewHeader(title = view.title.orEmpty(), handle = chrome.moveHandle)
        SwingPanel(
            background = MaterialTheme.colorScheme.surfaceContainer,
            factory = { session.component },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

/** The live view's handle bar: the element's title, and a drag there moves the element. */
@Composable
private fun LiveViewHeader(title: String, handle: Modifier) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(LettaDimens.Control.iconButton)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .then(handle)
            .padding(horizontal = LettaDimens.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Lucide.Puzzle,
            contentDescription = null,
            modifier = Modifier.size(LettaDimens.Control.icon),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.size(LettaDimens.Space.sm))
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The card, with a one-line notice over it: why the element is not live (yet). */
@Composable
private fun PluginCardWithNotice(view: PluginElementView, chrome: PluginElementChrome, notice: String) {
    Box(modifier = Modifier.fillMaxSize()) {
        PluginFallbackCard(view, chrome)
        Surface(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(LettaDimens.Space.sm)
                .testTag(PluginViewTestTags.notice(view.element.id)),
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

/** The notices a card wears while its element cannot be live. */
internal object PluginViewNotices {
    private const val PERCENT = 100

    fun preparing(state: BrowserRuntimeState.Preparing): String {
        val percent = state.progress?.let { " ${(it * PERCENT).toInt()}%" }.orEmpty()
        return when (state.stage) {
            "downloading" -> "Downloading the web view$percent (first run)"
            "extracting", "install" -> "Installing the web view$percent (first run)"
            else -> "Starting the web view$percent"
        }
    }
}

/** Test tags of the desktop live view. */
internal object PluginViewTestTags {
    fun live(id: String): String = "canvas-plugin-live-$id"

    fun notice(id: String): String = "canvas-plugin-notice-$id"
}
