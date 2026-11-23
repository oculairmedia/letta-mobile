package com.letta.mobile.ui.shell.tokens

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Sizes that belong to one shell surface each (the Home fleet table's columns, the header's search
 * trigger, a page's search field), named here so the pages read them instead of writing literals.
 * Anything on the general scale (spacing, controls, panes) comes from LettaDimens instead.
 */
internal object ShellDimens {
    /** A page's search field beside its title on a wide page (Home, Channels, Skills). */
    val pageSearchWidth: Dp = 320.dp

    /** A phone app bar's height. A pointer window's title bar sets its own. */
    val headerTouchMinHeight: Dp = 64.dp

    /** The header's search trigger on a pointer window. */
    val headerSearchWidth: Dp = 260.dp

    /** The two-colour Baken mark over the wordmark on Home's connection hero. */
    val heroMark: Dp = 72.dp

    /**
     * The rail's Home button: a tile the size of an agent orb (LettaDimens.Orb.lg) holding the two-colour mark, which
     * fills most of it so Home carries the same visual weight as the agents under it.
     */
    val railHomeTile: Dp = 40.dp
    val railHomeMark: Dp = 30.dp

    /** One agent in Home's avatar row: the avatar and its name under it. */
    val homeAvatarTile: Dp = 64.dp

    /**
     * Home greeting lineup: a mascot's tile at its three sizes. The rig draws the character across
     * [homeLineupBodyShare] of the tile, so neighbouring tiles overlap and the bodies sit close.
     */
    val homeLineupSmall: Dp = 72.dp
    val homeLineupMedium: Dp = 84.dp
    val homeLineupLarge: Dp = 100.dp

    /** The Baken mark's tile when it stands in the lineup: the smallest member. */
    val homeLineupMark: Dp = 60.dp

    /** How far a lineup member sits above or below the row's line, at most (in steps of half of it). */
    val homeLineupLift: Dp = 6.dp

    /** One step of the gap between two lineup bodies: from a slight overlap to a slight gap. */
    val homeLineupNudge: Dp = 2.dp

    /** The share of a mascot's tile its body covers. */
    const val homeLineupBodyShare: Float = 0.6f

    /** One step of a lineup member's tilt, in degrees (two steps either way at most). */
    const val homeLineupTiltStep: Float = 1.5f

    /** One tile of Home's pinned grid. */
    val homePinTileHeight: Dp = 108.dp

    /** A pinned tile's lift while it is dragged. */
    val homePinDragElevation: Dp = 8.dp

    /** The Home fleet table's fixed columns: model, chat count, last activity and the 48-hour strip. */
    val fleetModelColumn: Dp = 150.dp
    val fleetCountColumn: Dp = 56.dp
    val fleetTimeColumn: Dp = 84.dp
    val fleetSparkColumn: Dp = 72.dp

    /** The bars inside the fleet table's strip column. */
    val fleetSparkBars: Dp = 60.dp

    /** A schedule's detail sheet on a phone. */
    val scheduleDetailSheetHeight: Dp = 520.dp

    /** The new-schedule form pane: wide enough for the cadence presets on one row. */
    val scheduleFormWidth: Dp = 560.dp

    /** Readable measure for the connection hero's centred prose (narrower than the chat column on purpose). */
    val connectionProseMaxWidth: Dp = 520.dp

    /** How far the thinking aura's glow spreads past the agent's silhouette. */
    val thinkingAuraBlur: Dp = 6.dp

    /** A conversation row's settled / pinned marks: smaller than the small glyph, so two sit beside the title. */
    val conversationMarkIcon: Dp = 12.dp
}
