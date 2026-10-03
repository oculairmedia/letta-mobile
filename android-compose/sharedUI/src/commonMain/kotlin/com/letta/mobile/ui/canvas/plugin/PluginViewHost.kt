package com.letta.mobile.ui.canvas.plugin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.letta.mobile.data.plugin.PluginCapability
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.canvas_plugin_untitled
import com.letta.mobile.ui.canvas.LocalCanvasCompact
import com.letta.mobile.ui.theme.LettaDimens
import org.jetbrains.compose.resources.stringResource
import com.letta.mobile.data.plugin.view.PluginViewPermissions
import com.letta.mobile.data.plugin.view.PluginViewSpec
import com.letta.mobile.data.plugin.view.PluginViewTransport
import com.letta.mobile.data.plugin.view.ViewBridge

/**
 * Shows a plugin page live inside its element on the board (plan section 7.3,
 * letta-mobile-s416w.13): the Android WebView, desktop JCEF (s416w.14), later a wasm iframe. A
 * shell provides one through [LocalPluginViewHost]; with none, every element is drawn by the
 * fallback card ([PluginViewSlot]).
 *
 * What an implementation does with a [PluginViewSession]:
 *  - loads the page from [PluginViewSession.readPage], sandboxed, under the page's
 *    Content-Security-Policy and Permissions-Policy (`PluginViewCsp`, `PluginViewPermissions`) and
 *    the page's network allowlists, with `LcpViewShim` injected before the page's own scripts;
 *  - wires the shim's `window.__lettaViewPost` to [PluginPageChannel.postFromPage] and delivers
 *    [PluginPageChannel.toPage] through `window.__lettaViewReceive`;
 *  - runs [ViewBridge.run] while the page lives and, when the view leaves the composition, calls
 *    [ViewBridge.teardown] before it destroys the page (so the teardown outlives the composition),
 *    then closes [PluginViewSession.page];
 *  - calls `onFailure` when it cannot show the page (no engine on this device, the page failed to
 *    load, the engine died). The board then draws the fallback card instead.
 */
@Stable
interface PluginViewHost {
    @Composable
    fun View(session: PluginViewSession, modifier: Modifier, onFailure: (reason: String) -> Unit)
}

/** The shell's live-view host, or null (the default) when plugin elements are drawn by the fallback card only. */
val LocalPluginViewHost = staticCompositionLocalOf<PluginViewHost?> { null }

/**
 * One live view a [PluginViewHost] shows: the [bridge] the page talks to, the page end of the
 * bridge's port ([page]), how the page's HTML is read, and the first-use device permissions the
 * person granted the plugin on this device.
 */
@Stable
class PluginViewSession(
    val bridge: ViewBridge,
    val page: PluginPageChannel,
    private val transport: PluginViewTransport,
    private val granted: Set<PluginCapability> = emptySet(),
) {
    val spec: PluginViewSpec get() = bridge.spec

    /** The device permissions the page may use: those it declares that the person also granted. */
    val permissions: Set<PluginCapability> get() = PluginViewPermissions.effective(spec.page, granted)

    /** The page's HTML; throws `PluginViewUnavailableException` when the host cannot be reached. */
    suspend fun readPage(): ByteArray = transport.readPage(spec.pageRef)
}

/**
 * The frame a live view is drawn in: the fallback card's surface and handle bar (a drag there
 * moves the element), with [content] filling the rest instead of the snapshot.
 */
@Composable
fun PluginLiveCard(
    view: PluginElementView,
    chrome: PluginElementChrome,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    val title = view.title ?: stringResource(Res.string.canvas_plugin_untitled)
    Surface(
        modifier = modifier.fillMaxSize().testTag(CanvasPluginTestTags.live(view.element.id)),
        shape = RoundedCornerShape(LettaDimens.Radius.md),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(LettaDimens.Stroke.hairline, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = LettaDimens.Space.xs,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            PluginCardHeader(title = title, icon = pluginIcon(view.element.fallback.icon), handle = chrome.moveHandle, touch = LocalCanvasCompact.current)
            content(Modifier.weight(1f).fillMaxWidth())
        }
    }
}

/**
 * Draws [view] live through the provided [PluginViewHost] with [live], or with the fallback card
 * when the shell provides no host.
 */
@Composable
fun PluginViewSlot(
    view: PluginElementView,
    chrome: PluginElementChrome,
    modifier: Modifier = Modifier,
    live: @Composable (PluginViewHost) -> Unit,
) {
    val host = LocalPluginViewHost.current
    if (host == null) PluginFallbackCard(view, chrome, modifier) else live(host)
}
