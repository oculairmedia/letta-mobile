package com.letta.mobile.ui.memory

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.letta.mobile.data.memory.MemoryAccentRole
import com.letta.mobile.data.memory.MemoryCategories
import com.letta.mobile.data.memory.MemoryCategory
import com.letta.mobile.data.memory.MemoryGraphNode
import com.letta.mobile.data.memory.MemoryGraphNodeKind
import com.letta.mobile.data.memory.accentRole
import com.letta.mobile.ui.theme.customColors

/**
 * Theme colours the memory graph paints with, resolved once per composition so
 * the Canvas draw pass stays a plain lookup. Memory blocks take their semantic
 * category colour; every other node takes its kind's accent role.
 */
@Immutable
data class MemoryGraphPalette(
    val accents: Map<MemoryAccentRole, Color>,
    val categories: Map<MemoryCategory, Color>,
    val edge: Color,
    val edgeHighlight: Color,
    val nodeRing: Color,
    val selectionRing: Color,
    val label: Color,
    val labelMuted: Color,
) {
    fun nodeColor(node: MemoryGraphNode): Color =
        if (node.kind == MemoryGraphNodeKind.Memory) {
            categoryColor(MemoryCategories.categorize(node.title))
        } else {
            accentColor(node.kind.accentRole(node.status))
        }

    fun kindColor(kind: MemoryGraphNodeKind): Color = accentColor(kind.accentRole(null))

    fun accentColor(role: MemoryAccentRole): Color = accents[role] ?: label

    fun categoryColor(category: MemoryCategory): Color = categories[category] ?: label
}

@Composable
fun rememberMemoryGraphPalette(): MemoryGraphPalette {
    val scheme = MaterialTheme.colorScheme
    val custom = MaterialTheme.customColors
    return MemoryGraphPalette(
        accents = mapOf(
            MemoryAccentRole.Primary to scheme.primary,
            MemoryAccentRole.Secondary to scheme.secondary,
            MemoryAccentRole.Tertiary to scheme.tertiary,
            MemoryAccentRole.Neutral to scheme.onSurfaceVariant,
            MemoryAccentRole.Error to scheme.error,
        ),
        categories = mapOf(
            MemoryCategory.Persona to custom.categoryPersonaColor.orElse(scheme.primary),
            MemoryCategory.Human to custom.categoryHumanColor.orElse(scheme.secondary),
            MemoryCategory.Onboarding to custom.categoryOnboardingColor.orElse(scheme.tertiary),
            MemoryCategory.Project to custom.categoryProjectColor.orElse(scheme.secondary),
            MemoryCategory.Archival to custom.categoryArchivalColor.orElse(scheme.onSurfaceVariant),
        ),
        edge = scheme.outlineVariant,
        edgeHighlight = scheme.primary,
        nodeRing = scheme.surface,
        selectionRing = scheme.primary,
        label = scheme.onSurface,
        labelMuted = scheme.onSurfaceVariant,
    )
}

private fun Color.orElse(fallback: Color): Color = if (this == Color.Unspecified) fallback else this

fun memoryNodeKindLabel(kind: MemoryGraphNodeKind): String = when (kind) {
    MemoryGraphNodeKind.Agent -> "Agent"
    MemoryGraphNodeKind.Backend -> "Backend"
    MemoryGraphNodeKind.Skill -> "Skill"
    MemoryGraphNodeKind.Memory -> "Memory"
    MemoryGraphNodeKind.Schedule -> "Schedule"
    MemoryGraphNodeKind.Channel -> "Channel"
}
