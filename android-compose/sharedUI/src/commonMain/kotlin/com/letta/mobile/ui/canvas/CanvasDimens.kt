package com.letta.mobile.ui.canvas

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * The board's own sizes: the geometry of its floating chrome, its touch targets and its panels.
 *
 * These are facts about the canvas (how far the selection bar keeps from the title row, how big a
 * quick-create disc is under a finger), not points on the shared spacing scale, so they are named
 * here once instead of written where they are used. Spacing, icon and control sizes still come from
 * LettaDimens; a value belongs here only when no shared token means the same thing.
 */

/** Room above the board kept for the title and actions pills, which the selection bar stays below. */
internal val CANVAS_TOP_CHROME_CLEARANCE: Dp = 64.dp

/** Room below the board kept for the zoom pill and status line, which the selection bar stays above. */
internal val CANVAS_FOOT_CHROME_CLEARANCE: Dp = 72.dp

/** The tool rail's distance from the top of the board, clear of the title pill. */
internal val CANVAS_RAIL_TOP_CLEARANCE: Dp = 72.dp

/** The tool rail's distance from the foot of the board, clear of the zoom pill. */
internal val CANVAS_RAIL_BOTTOM_CLEARANCE: Dp = 64.dp

/** The tool rail's width and its inset: what the floating selection bar keeps clear of on the left. */
internal val RAIL_CLEARANCE: Dp = 64.dp

/** Below this width the board is phone-sized: Material's compact window class. */
internal val COMPACT_CANVAS_WIDTH: Dp = 600.dp

/**
 * Room kept at the top of the board when the keyboard moves the camera: for the title and sync bar
 * where there is one, and for the selection bar (a note's colour and size) floating over the target.
 */
internal val KEYBOARD_TOP_RESERVE: Dp = 72.dp

/** How far the selection bar floats above a quick-create target, so it never covers the target. */
internal val QUICK_CREATE_BAR_GAP: Dp = 56.dp

/** The line of the arrow a quick-create drag draws from its source. */
internal val QUICK_ARROW_STROKE: Dp = 3.5.dp

/** A quick-create disc beside a selection under a mouse: its size, glyph and distance from the edge. */
internal val QUICK_CREATE_TARGET: Dp = 26.dp
internal val QUICK_CREATE_ICON: Dp = 14.dp
internal val QUICK_CREATE_OFFSET: Dp = 34.dp

/** The same disc under a finger: Material's touch minimum is 48dp, and the disc sits a little inside that. */
internal val QUICK_CREATE_TOUCH_TARGET: Dp = 40.dp
internal val QUICK_CREATE_TOUCH_ICON: Dp = 20.dp
internal val QUICK_CREATE_TOUCH_OFFSET: Dp = 46.dp

/** A quick-create disc's lift off the board. */
internal val QUICK_CREATE_ELEVATION: Dp = 2.dp

/** The note editor's foot-bar and back buttons: Keep-sized, a fingertip's reach on a phone. */
internal val NOTE_EDITOR_BUTTON: Dp = 40.dp

/** A fingertip covers far more than a line is wide. The mouse and pen keep DrawBox's 12dp. */
internal val FINGER_PICK_TOLERANCE: Dp = 24.dp
internal val POINTER_PICK_TOLERANCE: Dp = 12.dp

/** The selection chrome under a finger: handle diameter, how near a press still grabs one, and the rotation handle's lift. */
internal val SELECTION_TOUCH_HANDLE: Dp = 16.dp
internal val SELECTION_TOUCH_HIT_RADIUS: Dp = 24.dp
internal val SELECTION_TOUCH_ROTATION_OFFSET: Dp = 44.dp

/** What a centred note editor row adds to its measured text, so the caret never clips. */
internal val CENTRED_SLACK: Dp = 4.dp

/** The phone tool bar's buttons: big enough for a fingertip, small enough that the bar fits a 360dp phone. */
internal val COMPACT_TOOL_BUTTON: Dp = 40.dp

/** The board title's widest, and its width on a phone, where it leaves the row to the actions pill. */
internal val CANVAS_TITLE_MAX_WIDTH: Dp = 220.dp
internal val COMPACT_TITLE_WIDTH: Dp = 120.dp

/** The status line's widest: a readable line in the board's corner, never a banner. */
internal val CANVAS_STATUS_MAX_WIDTH: Dp = 360.dp

/** The sync badge's words at their widest before they ellipsize. */
internal val SYNC_BADGE_MAX_WIDTH: Dp = 280.dp

/** The colour picker popover's width. */
internal val COLOR_PICKER_WIDTH: Dp = 260.dp

/** The property panel: its width beside a desktop control, and on a phone. */
internal val PROPERTY_PANEL_WIDTH: Dp = 280.dp

/** Narrow enough to sit on a 360dp phone with room either side. */
internal val COMPACT_PROPERTY_PANEL_WIDTH: Dp = 296.dp

/** Short enough to leave the element it edits in view. */
internal val COMPACT_PROPERTY_PANEL_MAX_HEIGHT: Dp = 320.dp

/** The note editor floating over a wide board: its gap from the board's top and foot, and its widest. */
internal val NOTE_EDITOR_VERTICAL_MARGIN: Dp = 56.dp
internal val NOTE_EDITOR_MAX_WIDTH: Dp = 880.dp

/** The note editor in a phone's sheet: never shorter than this, so the body always has room. */
internal val NOTE_EDITOR_SHEET_MIN_HEIGHT: Dp = 280.dp

/** The history dialog's list: tall enough for several checkpoints, short enough to fit a phone. */
internal val HISTORY_LIST_HEIGHT: Dp = 300.dp

/** The document viewer toggle's distance below the board's own chrome. */
internal val VIEWER_TOGGLE_TOP_OFFSET: Dp = 56.dp

/*
 * A note's own ink on its own colour. The note's colour is data, not a theme role, so these alphas
 * are facts about a note card, kept at the values the note editor was designed with.
 */

/** A note editor glyph that cannot be used now (no checklist to add yet): its ink, faded. */
internal const val NOTE_GLYPH_DISABLED_ALPHA: Float = 0.38f

/** The empty title's "Title" placeholder in the note's ink. */
internal const val NOTE_TITLE_PLACEHOLDER_ALPHA: Float = 0.5f

/** The fill behind the note editor button whose panel is open. */
internal const val NOTE_BUTTON_SELECTED_FILL_ALPHA: Float = 0.12f

/** The tinted band a note card's drag handle sits on. */
internal const val NOTE_HANDLE_BAND_ALPHA: Float = 0.08f
