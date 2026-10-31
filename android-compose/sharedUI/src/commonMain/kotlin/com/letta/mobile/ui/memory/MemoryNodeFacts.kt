package com.letta.mobile.ui.memory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import com.letta.mobile.data.memory.MemoryChannelStatus
import com.letta.mobile.data.memory.MemoryGraphNodeKind
import com.letta.mobile.data.memory.MemoryParityItem
import com.letta.mobile.data.memory.MemoryParitySummary
import com.letta.mobile.ui.components.LettaFactLabel
import com.letta.mobile.ui.components.LettaFactRow
import com.letta.mobile.ui.components.LettaListRow
import com.letta.mobile.ui.components.LettaListRowSpec
import com.letta.mobile.ui.components.LettaMeter
import com.letta.mobile.ui.components.LettaSectionLabel
import com.letta.mobile.ui.components.LettaSwatch
import com.letta.mobile.ui.components.lettaFraction
import com.letta.mobile.ui.components.lettaPercent
import com.letta.mobile.ui.components.lettaUsage
import com.letta.mobile.ui.theme.LettaDimens

/** The context window as a meter: "801 / 1,000,000 tokens · 0.1%". */
@Composable
internal fun MemoryContextMeter(summary: MemoryParitySummary, modifier: Modifier = Modifier) {
    val used = summary.contextWindowUsed
    val limit = summary.contextWindowLimit
    val memoryTokens = summary.totalMemoryTokens
    val text = when {
        !summary.contextLoaded -> "—"
        used != null && limit != null -> lettaUsage(used, limit, "tokens") + " · " + lettaPercent(used, limit)
        used != null -> lettaUsage(used, null, "tokens")
        memoryTokens != null -> lettaUsage(memoryTokens, null, "memory tokens")
        else -> "Unavailable"
    }
    val fraction = used?.let { lettaFraction(it, limit) }
    LettaMeter("Context", text, fraction, MaterialTheme.colorScheme.primary, modifier)
}

/** The agent: its context window, then its blocks (tap one to open it) and what else it links to. */
@Composable
internal fun MemoryAgentFacts(card: MemoryNodeCardState, palette: MemoryGraphPalette, onOpen: (String) -> Unit) {
    MemoryContextMeter(card.summary, Modifier.fillMaxWidth())
    val blocks = card.related.filter { it.kind == MemoryGraphNodeKind.Memory }.sortedByDescending { it.length ?: 0 }
    if (blocks.isNotEmpty()) {
        LettaSectionLabel("Memory blocks")
        blocks.forEach { MemoryRelatedBlockRow(it, palette.kindColor(MemoryGraphNodeKind.Memory), onOpen) }
    }
    val others = card.related.filter { it.kind != MemoryGraphNodeKind.Memory }
    if (others.isNotEmpty()) {
        LettaSectionLabel("Linked")
        others.forEach { node ->
            LettaListRow(
                spec = LettaListRowSpec(title = node.title, trailing = memoryNodeKindLabel(node.kind)),
                onClick = { onOpen(node.nodeId) },
                leading = { LettaSwatch(palette.kindColor(node.kind)) },
            )
        }
    }
}

/** A block in the agent's list: its size against its limit, as a meter you can tap to open. */
@Composable
private fun MemoryRelatedBlockRow(block: MemoryRelatedNode, colour: androidx.compose.ui.graphics.Color, onOpen: (String) -> Unit) {
    val length = block.length ?: 0
    LettaMeter(
        label = block.title,
        value = lettaUsage(length, block.limit, "chars"),
        fraction = lettaFraction(length, block.limit),
        colour = colour,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable { onOpen(block.nodeId) }
            .padding(horizontal = LettaDimens.Space.sm, vertical = LettaDimens.Space.sm),
    )
}

/** A block: how full it is against its limit, and whether it can be written. */
@Composable
internal fun MemoryBlockFacts(card: MemoryNodeCardState) {
    val block = card.item as? MemoryParityItem.MemoryBlock
    val length = card.blockLength ?: 0
    val limit = block?.limit ?: card.selection.detail.limit
    val text = lettaUsage(length, limit, "chars") + (limit?.let { " · " + lettaPercent(length, it) } ?: "")
    LettaMeter("Size", text, lettaFraction(length, limit), card.accent, Modifier.fillMaxWidth())
    if (card.selection.detail.readOnly) LettaFactRow("Access", "Read-only")
}

/** A schedule: how it repeats, its cron expression, and when it next runs. */
@Composable
internal fun MemoryScheduleFacts(schedule: MemoryParityItem.Schedule) {
    val recurring = schedule.scheduleType == "recurring"
    LettaFactRow("Repeats", if (recurring) "Recurring" else "One-time")
    val cron = schedule.subtitle.substringAfter("Recurring: ", "")
    if (recurring && cron.isNotBlank()) LettaFactRow("Cron", cron, monospace = true)
    LettaFactRow("Next run", schedule.nextRunLabel)
}

/** A channel: its live status and the address it delivers to. */
@Composable
internal fun MemoryChannelFacts(channel: MemoryParityItem.Channel, palette: MemoryGraphPalette) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
        LettaFactLabel("Status")
        LettaSwatch(palette.statusColor(channel.status))
        Text(channel.status.label(), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
    val address = listOf(channel.title, channel.subtitle, channel.detailText).firstOrNull { "://" in it } ?: channel.subtitle
    LettaFactRow("Address", address, monospace = "://" in address)
}

/** A skill: its type and tags. */
@Composable
internal fun MemorySkillFacts(skill: MemoryParityItem.Skill) {
    LettaFactRow("Type", skill.type)
    if (skill.tags.isNotEmpty()) LettaFactRow("Tags", skill.tags.joinToString(", "))
}

private fun MemoryChannelStatus.label(): String = when (this) {
    MemoryChannelStatus.Connected -> "Connected"
    MemoryChannelStatus.Connecting -> "Connecting"
    MemoryChannelStatus.Idle -> "Idle"
    MemoryChannelStatus.Disconnected -> "Disconnected"
}
