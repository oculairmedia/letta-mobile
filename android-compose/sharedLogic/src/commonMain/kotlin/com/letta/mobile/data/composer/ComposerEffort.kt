package com.letta.mobile.data.composer

/**
 * Reasoning-effort levels offered in the composer effort popover (Penpot
 * "Effort popover (real composer)"): Minimal … Max. Shared in commonMain so the
 * desktop popover and the mobile effort sheet present the same ordered set.
 */
enum class ComposerEffort(val label: String) {
    Minimal("Minimal"),
    Low("Low"),
    Medium("Medium"),
    High("High"),
    Max("Max"),
}
