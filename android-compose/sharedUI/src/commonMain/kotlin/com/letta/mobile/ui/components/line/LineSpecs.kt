package com.letta.mobile.ui.components.line

import androidx.compose.runtime.Immutable
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_collapse
import com.letta.mobile.sharedui.resources.rows_expand
import com.letta.mobile.sharedui.resources.rows_state_collapsed
import com.letta.mobile.sharedui.resources.rows_state_expanded
import org.jetbrains.compose.resources.StringResource

/*
 * The plain values the shared line components draw from (StatusGlyph, SummaryLine, StepLine,
 * DividerLine and the disclosure toggle in ui.components). They live in this leaf package, importing
 * nothing of the UI, so any row package can name them without joining an import cycle.
 */

/** What a status glyph says, and so which glyph and tint it draws. */
internal enum class StatusTone {
    /** Still working: a small spinner (a still ring where motion is off). */
    Working,

    /** Waits on the person: a warning in the secondary tint. */
    Attention,

    /** Failed: an error mark in the error tint. */
    Failed,

    /** Settled cleanly: a muted check. */
    Done,

    /** A moment of success (a copy that landed): a check in the success tint. */
    Success,

    /** A neutral notice (a Monitor event): an info mark, muted. */
    Info,

    /** A watch that keeps reporting (a Monitor watcher): an eye, muted. */
    Watching,
}

/**
 * Which column a line sits in. Every line on one rail shares its glyph start, glyph size, label
 * start and style, chevron edge and size, and its height floor.
 */
internal enum class LineRail {
    /** A sibling of a run's header: the run header, a notice between runs, a Monitor event. */
    Header,

    /** One of a run's work steps, listed under its header: a tool call, a reasoning, a folded report. */
    Step,

    /** Inside an open step's details: flush with the step's label, inside its chevron's edge. */
    Nested,
}

/** The test tags a line carries: on the line (its toggle) and on its glyph. */
@Immutable
internal class LineTags(val row: String, val glyph: String)

/** What a line shows: its label, an optional preview under it, its glyph and the glyph's spoken state. */
@Immutable
internal class LineSpec(
    val title: String,
    val tone: StatusTone,
    val tags: LineTags,
    val subtitle: String? = null,
    val glyphLabel: String? = null,
)

/** What a disclosure says: its action labels and the line's spoken open / closed state. */
@Immutable
internal class LineDisclosureLabels(
    val expand: StringResource,
    val collapse: StringResource,
    val expandedState: StringResource,
    val collapsedState: StringResource,
)

/** The generic labels: "Expand" / "Collapse", spoken "Expanded" / "Collapsed". */
internal val DefaultDisclosureLabels: LineDisclosureLabels = LineDisclosureLabels(
    Res.string.rows_expand,
    Res.string.rows_collapse,
    Res.string.rows_state_expanded,
    Res.string.rows_state_collapsed,
)

/** A line's disclosure: whether what it discloses is open, how to flip it, and what it says. */
@Immutable
internal class LineDisclosure(
    val expanded: Boolean,
    val labels: LineDisclosureLabels = DefaultDisclosureLabels,
    val onToggle: () -> Unit,
)

/** Which way a shared arrow points. */
internal enum class ArrowDirection {
    /** Down to the newest content. */
    Down,

    /** Back to the previous page. */
    Back,

    /** On to the next page. */
    Forward,
}

/** One of a pager's two steps: what it says, whether it can go, where it goes, and its test tag. */
@Immutable
internal class PagerStep(
    val contentDescription: String,
    val enabled: Boolean,
    val onClick: () -> Unit,
    val tag: String = "",
)
