package com.letta.mobile.ui.theme.tokens

/**
 * What a container says by its fill (LettaPanel, LettaInlineNotice, LettaInlineError). Each tone maps
 * to one Material container role, so the same tone reads the same on every page.
 */
enum class LettaPanelTone {
    /** A plain grouping: `surfaceContainer`. */
    Neutral,

    /** Something to know, nothing wrong: `secondaryContainer`. */
    Info,

    /** Something that needs attention soon: `tertiaryContainer`. */
    Warning,

    /** Something failed: `errorContainer`. */
    Error,
}

/**
 * The state a status dot (LettaStatusDot, LettaStatusLabel) shows. Each tone is one colour on every
 * page, so "running" never means two colours.
 */
enum class LettaStatusTone {
    /** Nothing happening, not connected, local only: `outline`. */
    Idle,

    /** Working, thinking, connecting, syncing: `primary`. */
    Running,

    /** Done, connected, synced, online: the theme's success colour. */
    Success,

    /** Degraded, queued, reconnecting: the theme's warning (amber) colour. */
    Warning,

    /** Failed: `error`. */
    Error,
}
