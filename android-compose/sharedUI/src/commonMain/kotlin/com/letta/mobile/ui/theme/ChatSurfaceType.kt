package com.letta.mobile.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/*
 * The chat surface's type roles beyond the plain Material scale (composer, subagent roster and
 * sheets, background process shelf, run status line, agent-message provenance), read as
 * `MaterialTheme.typography.chatLabelEmphasis`. Each derives from the host's own role, so the Letta
 * scale still applies; one emphasis weight and one monospace family, so two emphasised labels never
 * disagree.
 */

private val ChatEmphasisWeight = FontWeight.SemiBold

/** A small emphasised label: a shelf's heading, a process kind ("SHELL"). */
internal val Typography.chatLabelEmphasis: TextStyle get() = labelSmall.copy(fontWeight = ChatEmphasisWeight)

/** A popover's or panel's title line ("Context"). */
internal val Typography.chatPanelTitle: TextStyle get() = labelLarge.copy(fontWeight = ChatEmphasisWeight)

/** A list row's emphasised name (a slash command in the suggestions). */
internal val Typography.chatRowName: TextStyle get() = bodyMedium.copy(fontWeight = ChatEmphasisWeight)

/** A card's title (a subagent in the roster). */
internal val Typography.chatCardTitle: TextStyle get() = titleSmall.copy(fontWeight = ChatEmphasisWeight)

/** Machine text at label size: ids, transports, routing details. */
internal val Typography.chatMonoLabel: TextStyle get() = labelSmall.copy(fontFamily = FontFamily.Monospace)

/** Machine text at body size: a command's output, a subagent's activity line. */
internal val Typography.chatMonoBody: TextStyle get() = bodySmall.copy(fontFamily = FontFamily.Monospace)
