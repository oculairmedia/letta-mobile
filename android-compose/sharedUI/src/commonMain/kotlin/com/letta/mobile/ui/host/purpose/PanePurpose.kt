package com.letta.mobile.ui.host.purpose

/**
 * What a modal pane (dialog, alert, sheet) is for, which decides how it may be dismissed:
 *  - [Info]: something to read. A tap outside, a swipe down, Escape and back all close it.
 *  - [Form]: something being filled in. A stray tap outside or a swipe down does not close it, so a
 *    half-typed form is never lost by accident; Escape, back and its own Cancel still do (deliberate).
 *  - [Required]: something that must be answered. Only its own buttons close it.
 */
enum class PanePurpose {
    Info,
    Form,
    Required,
    ;

    /** Whether a tap outside (the scrim) or a swipe down may close it. */
    val dismissesOnOutside: Boolean get() = this == Info

    /** Whether Escape, back or a window's own close may close it. */
    val dismissesOnKey: Boolean get() = this != Required
}
