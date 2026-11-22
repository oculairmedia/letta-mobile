package com.letta.mobile.ui.theme.tokens

/**
 * How long a control's own feedback stays before it falls back, in milliseconds. A leaf token, so the
 * shared components in ui.components.foundation can use it without importing ui.theme.
 */
object LettaFeedbackMotion {
    /** How long a Copy shows its check and says "Copied". */
    const val COPIED_MILLIS: Long = 1200L
}
