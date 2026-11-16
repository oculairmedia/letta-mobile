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

/**
 * The colour set a border beam (lettaBorderBeam) runs in. Each tone maps to theme roles except
 * [Spectrum], the one multicolour palette ([LettaBeamPalette.spectrum]).
 */
enum class LettaBeamTone {
    /** Something is working (a run, a stream, a recording): `primary` into `tertiary`. */
    Working,

    /** Something wants attention now (approval, failure about to need a decision): `error`. */
    Attention,

    /** The multicolour beam for a featured or celebratory surface. */
    Spectrum,
}

