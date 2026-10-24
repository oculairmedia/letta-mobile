package com.letta.mobile.ui.host

/**
 * Said when a Paste could not read the clipboard: a browser page without
 * clipboard-read permission. The keyboard paste still works there, without asking.
 */
internal const val PASTE_BLOCKED_HINT = "Clipboard access is blocked here. Click the field and press Ctrl+V (Cmd+V on Mac) to paste."

/** Shows [PASTE_BLOCKED_HINT]; another refusal while it shows replaces it rather than queueing a second. */
internal fun OverlayHost.showPasteBlockedHint(): OverlayHandle =
    show(Overlay.Snackbar(PASTE_BLOCKED_HINT, key = PASTE_BLOCKED_KEY, presentation = OverlayPresentation.Long))

private const val PASTE_BLOCKED_KEY = "paste-blocked"
