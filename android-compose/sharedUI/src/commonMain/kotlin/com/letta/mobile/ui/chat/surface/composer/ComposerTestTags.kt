package com.letta.mobile.ui.chat.surface.composer

/** Test tags of the shared composer. Desktop's tests used the first two. */
internal object ComposerTestTags {
    const val INPUT = "composer-input"
    const val SEND = "composer-send"
    const val STOP = "composer-stop"
    const val CARD = "composer-card"
    const val CONTROLS = "composer-controls"
    const val DOCKED_BAR = "composer-docked"
    const val DOCKED_INPUT = "composer-docked-input"
    const val EXPAND = "composer-expand"
    const val SHOW_CANVAS = "composer-show-canvas"
    const val ERROR = "composer-error"
    const val HINT = "composer-hint"
    /** The line under the card: the permission mode, the keyboard hint and, at its right, the context indicator. */
    const val HINT_FOOTER = "composer-hint-footer"
    const val COMMAND_SUGGESTIONS = "composer-commands"
    const val COMMAND_ROW = "composer-command-"
    const val ATTACHMENTS = "composer-attachments"
    /** The one model control: the model, with its effort as a suffix; it opens the picker. */
    const val MODEL_CHIP = "composer-model"
    /** The context indicator: a plain reading (no chip, no chevron) that opens its breakdown. */
    const val CONTEXT_CHIP = "composer-context"
    const val CONTEXT_POPOVER = "composer-context-popover"
    /** The chevron a chip that opens a picker ends with. */
    const val CHIP_CHEVRON = "composer-chip-chevron"
    const val WORKING_DIRECTORY = "composer-working-directory"
    /** The permission mode's quiet line under the prompt, which opens its popover. */
    const val PERMISSION_MODE_LINE = "composer-permission-mode"
    /** The Touch bar's reserved line under its row, which holds [PERMISSION_MODE_LINE]. */
    const val TOUCH_UNDER_BAR = "composer-touch-under-bar"
    const val PERMISSION_MODE_POPOVER = "composer-permission-mode-popover"
    const val PERMISSION_MODE_OPTION = "composer-permission-mode-option-"
    const val PERMISSION_MODE_NOTE = "composer-permission-mode-note"
    const val PERMISSION_MODE_RUNTIME = "composer-permission-mode-runtime"

    /** The Touch bar and its parts. */
    const val TOUCH_BAR = "composer-touch-bar"
    const val TOUCH_PLUS = "composer-touch-plus"
    const val TOUCH_VOICE = "composer-touch-voice"
    const val TOUCH_SHEET = "composer-touch-sheet"
    const val TOUCH_SHEET_ROW = "composer-touch-sheet-row"
    const val TOUCH_RESTORE = "composer-touch-restore"
    /** Elapsed + running-tool readout beside the Touch companion. */
    const val TOUCH_COMPANION_STATUS = "composer-touch-companion-status"
}
