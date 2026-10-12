package com.letta.mobile.ui.host

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.letta.mobile.ui.components.LettaMenuItem
import com.letta.mobile.ui.shell.ShellLongPressMenu

/**
 * letta-mobile-ckmub.3.1 (hard cut S3, primitive P1): the one container contract.
 *
 * A surface never picks a platform container (ModalBottomSheet, Popup, DialogWindow,
 * ContextMenuArea). It asks for a [LettaPaneKind] through [LettaPane] and the [LettaHost] in effect
 * decides how that kind is drawn. Android keeps the [LettaHost.Touch] default; desktop and web
 * provide a pointer host once at their window root.
 */
enum class LettaInput { Touch, Pointer }

/** Where an anchored pane opens relative to the composable it is declared inside. */
enum class PaneAnchor { BelowStart, BelowEnd, AboveStart, AboveEnd }

/**
 * What a surface asks for. The concrete kinds name a container; [Adaptive] and [Modal] are intents
 * the host resolves by its input (see [LettaHost.resolve]).
 */
sealed interface LettaPaneKind {
    /** A bottom sheet. */
    data object Sheet : LettaPaneKind

    /** A small surface anchored to its declaring composable. */
    data class Popover(val anchor: PaneAnchor = PaneAnchor.BelowStart) : LettaPaneKind

    /** A centred modal card over a scrim. */
    data object Dialog : LettaPaneKind

    /** A short blocking question (a confirmation). A pointer desktop shows it as its own window. */
    data object Alert : LettaPaneKind

    /** A list of actions anchored to its declaring composable. */
    data class Menu(val anchor: PaneAnchor = PaneAnchor.BelowStart) : LettaPaneKind

    /** Sheet on touch, popover on pointer: detail that belongs to a control (the agent context card). */
    data class Adaptive(val anchor: PaneAnchor = PaneAnchor.BelowStart) : LettaPaneKind

    /** Sheet on touch, dialog on pointer: a task that takes over the page (the model picker). */
    data object Modal : LettaPaneKind
}

/** Size hints a container applies when it can; [Dp.Unspecified] keeps the container's default. */
@Immutable
data class PaneSize(val width: Dp = Dp.Unspecified, val maxHeight: Dp = Dp.Unspecified) {
    companion object {
        val Default = PaneSize()
    }
}

/** What every container receives besides its content. */
@Immutable
class PaneSpec(
    val onDismiss: () -> Unit,
    val title: String? = null,
    val anchor: PaneAnchor = PaneAnchor.BelowStart,
    val size: PaneSize = PaneSize.Default,
    /** Tags the content column, so tests find a surface whichever container drew it. */
    val testTag: String? = null,
    /** Applied to the container's frame (a menu's tag, a sheet's semantics). */
    val modifier: Modifier = Modifier,
)

/** A host's container for one pane kind. */
typealias PaneContainer = @Composable (spec: PaneSpec, content: @Composable ColumnScope.() -> Unit) -> Unit

/** A host's menu container: like [PaneContainer], plus whether the menu is showing (it animates out). */
typealias MenuContainer = @Composable (expanded: Boolean, spec: PaneSpec, content: @Composable ColumnScope.() -> Unit) -> Unit

/** A richer hover: a title, the raw ISO-8601 time of the last activity (the host formats it), a short body. */
@Immutable
data class LettaHoverCard(
    val title: String,
    val timestamp: String? = null,
    val body: String? = null,
)

/** A destructive action waiting for the user's yes. */
@Immutable
data class LettaConfirmRequest(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val dismissLabel: String = "Cancel",
)

/**
 * The platform's containers. Every parameter defaults to the shared touch implementation, so a
 * platform overrides only what it draws differently (desktop: its own alert window, cursor tooltips,
 * right-click row menus).
 */
@Immutable
class LettaHost(
    val input: LettaInput = LettaInput.Touch,
    val sheet: PaneContainer = LettaPaneDefaults.Sheet,
    val popover: PaneContainer = LettaPaneDefaults.Popover,
    val dialog: PaneContainer = LettaPaneDefaults.Dialog,
    val alert: PaneContainer = LettaPaneDefaults.Alert,
    val menu: MenuContainer = LettaPaneDefaults.Menu,
    /** Wraps a control with a hover label. */
    val tooltip: @Composable (text: String, content: @Composable () -> Unit) -> Unit = { _, content -> content() },
    /** Wraps a control with a hover card (the rail's agent orbs). */
    val hoverCard: @Composable (card: LettaHoverCard, content: @Composable () -> Unit) -> Unit = { _, content -> content() },
    /** Wraps a row with its secondary actions: long-press on touch, right-click on a desktop pointer. */
    val rowMenu: @Composable (items: List<LettaMenuItem>, content: @Composable () -> Unit) -> Unit =
        { items, content -> ShellLongPressMenu(items, content) },
) {
    /** The concrete kind an intent becomes on this host. */
    fun resolve(kind: LettaPaneKind): LettaPaneKind = when (kind) {
        is LettaPaneKind.Adaptive -> if (input == LettaInput.Touch) LettaPaneKind.Sheet else LettaPaneKind.Popover(kind.anchor)
        LettaPaneKind.Modal -> if (input == LettaInput.Touch) LettaPaneKind.Sheet else LettaPaneKind.Dialog
        else -> kind
    }

    /** This host with some pieces replaced (a test's recording container, a desktop's tooltips). */
    fun copy(
        input: LettaInput = this.input,
        sheet: PaneContainer = this.sheet,
        popover: PaneContainer = this.popover,
        dialog: PaneContainer = this.dialog,
        alert: PaneContainer = this.alert,
        menu: MenuContainer = this.menu,
        tooltip: @Composable (String, @Composable () -> Unit) -> Unit = this.tooltip,
        hoverCard: @Composable (LettaHoverCard, @Composable () -> Unit) -> Unit = this.hoverCard,
        rowMenu: @Composable (List<LettaMenuItem>, @Composable () -> Unit) -> Unit = this.rowMenu,
    ): LettaHost = LettaHost(input, sheet, popover, dialog, alert, menu, tooltip, hoverCard, rowMenu)

    companion object {
        /** Android's host: sheets, long-press menus, no hover. */
        val Touch = LettaHost()

        /** A pointer host with the shared containers (web); desktop adds its window-level pieces. */
        val Pointer = LettaHost(input = LettaInput.Pointer)
    }
}

/** The host in effect; a platform provides it once at its window root. */
val LocalLettaHost = staticCompositionLocalOf { LettaHost.Touch }

/** Makes [host] the container host for [content]: a platform's window root calls this once. */
@Composable
fun ProvideLettaHost(host: LettaHost, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLettaHost provides host, content = content)
}
