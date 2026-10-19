package com.letta.mobile.ui.host

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import com.letta.mobile.ui.components.LettaSidePane
import com.letta.mobile.ui.components.LettaSidePaneHeader
import com.letta.mobile.ui.theme.LettaDimens

/**
 *  (hard cut S13, primitive P6): a secondary detail panel — the background
 * tasks roster, a tool run's calls, the agent's model and context — declared where its control is
 * and drawn where the window keeps details: docked on the right of the [InspectorFrame] when the
 * frame is wide, a bottom sheet ([LettaPane]) when it is compact.
 *
 * The pane is open while it is composed (`if (open) InspectorPane(...)`); [onClose] is how its
 * close control, a scrim tap, Escape or back ask for that. With no frame around it (a phone page,
 * a test) it opens as a [LettaPaneKind.Modal]: a sheet on touch, a dialog on a pointer.
 *
 * Several open at once stack: the newest shows, and closing it shows the one under it.
 */
@Composable
fun InspectorPane(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    width: Dp = InspectorDefaults.Width,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val host = LocalInspectorHost.current
    if (host == null) {
        LettaPane(LettaPaneKind.Modal, onDismiss = onClose, testTag = testTag, modifier = modifier) {
            LettaSidePaneHeader(title = title, onClose = onClose, actions = actions)
            content()
        }
        return
    }
    val spec = InspectorSpec(title, onClose, modifier, testTag, width, actions, content)
    val entry = remember(host) { InspectorEntry(spec) }
    SideEffect { entry.spec = spec }
    DisposableEffect(host, entry) {
        host.open(entry)
        onDispose { host.close(entry) }
    }
}

/**
 * Where the window keeps its inspector: [content] fills the frame and, while an [InspectorPane]
 * inside it (or anywhere under the same [host], see [ProvideInspectorHost]) is open, the pane
 * docks on the right when the frame is at least [dockedMinWidth] wide, or opens as a sheet over it
 * when narrower. [content] keeps its place either way, so crossing the breakpoint never rebuilds
 * the page. A frame owns its host unless one is passed: nested frames never draw one pane twice.
 */
@Composable
fun InspectorFrame(
    modifier: Modifier = Modifier,
    host: InspectorHost = rememberInspectorHost(),
    dockedMinWidth: Dp = InspectorDefaults.DockedMinWidth,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalInspectorHost provides host) {
        BoxWithConstraints(modifier) {
            val docked = inspectorPresentation(maxWidth, dockedMinWidth) == InspectorPresentation.Docked
            val entry = host.current
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()) { content() }
                if (docked && entry != null) DockedInspector(entry.spec)
            }
            if (!docked && entry != null) SheetInspector(entry.spec)
        }
    }
}

/** How a frame shows its inspector at a given width. */
enum class InspectorPresentation { Docked, Sheet }

/** The presentation an [InspectorFrame] of [frameWidth] uses. */
fun inspectorPresentation(frameWidth: Dp, dockedMinWidth: Dp = InspectorDefaults.DockedMinWidth): InspectorPresentation =
    if (frameWidth >= dockedMinWidth) InspectorPresentation.Docked else InspectorPresentation.Sheet

/**
 * The window's open inspectors. A shell root that draws its frame around only part of the window
 * (desktop: the main pane, not the sidebar) provides one host above both, so a control in the
 * sidebar opens its detail in the frame.
 */
@Stable
class InspectorHost {
    private val entries = mutableStateListOf<InspectorEntry>()

    /** The inspector on screen: the newest open one. */
    internal val current: InspectorEntry? get() = entries.lastOrNull()

    /** How many inspectors are open (the one on screen and those under it). */
    val openCount: Int get() = entries.size

    internal fun open(entry: InspectorEntry) {
        entries.remove(entry)
        entries.add(entry)
    }

    internal fun close(entry: InspectorEntry) {
        entries.remove(entry)
    }
}

/** The inspector host in effect; [InspectorFrame] provides one. */
val LocalInspectorHost = staticCompositionLocalOf<InspectorHost?> { null }

@Composable
fun rememberInspectorHost(): InspectorHost = remember { InspectorHost() }

/** Makes [host] the inspector host for [content], beyond the frame that draws it. */
@Composable
fun ProvideInspectorHost(host: InspectorHost, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalInspectorHost provides host, content = content)
}

/** Defaults for [InspectorPane] and [InspectorFrame]. */
object InspectorDefaults {
    /** A docked inspector's starting width (resizable from its left edge). */
    val Width: Dp = LettaDimens.Pane.sidePanelWidth

    /** The narrowest frame that docks its inspector; narrower frames open it as a sheet. */
    val DockedMinWidth: Dp = LettaDimens.Pane.wideBreakpoint
}

/** Tags the frame puts on the docked inspector. */
object InspectorTags {
    const val DOCKED = "inspector-docked"
}

internal class InspectorSpec(
    val title: String,
    val onClose: () -> Unit,
    val modifier: Modifier,
    val testTag: String?,
    val width: Dp,
    val actions: @Composable RowScope.() -> Unit,
    val content: @Composable ColumnScope.() -> Unit,
)

@Stable
internal class InspectorEntry(spec: InspectorSpec) {
    var spec: InspectorSpec by mutableStateOf(spec)
}

@Composable
private fun DockedInspector(spec: InspectorSpec) {
    LettaSidePane(
        title = spec.title,
        onClose = spec.onClose,
        initialWidth = spec.width,
        minWidth = minOf(spec.width, InspectorDefaults.Width),
        actions = spec.actions,
        modifier = spec.modifier.testTag(InspectorTags.DOCKED),
    ) {
        Column(Modifier.fillMaxSize().then(spec.testTag?.let { Modifier.testTag(it) } ?: Modifier)) { spec.content(this) }
    }
}

@Composable
private fun SheetInspector(spec: InspectorSpec) {
    LettaPane(LettaPaneKind.Sheet, onDismiss = spec.onClose, testTag = spec.testTag, modifier = spec.modifier) {
        LettaSidePaneHeader(title = spec.title, onClose = spec.onClose, actions = spec.actions, modifier = Modifier.fillMaxWidth())
        spec.content(this)
    }
}
