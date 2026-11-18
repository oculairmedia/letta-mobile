package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.letta.mobile.data.model.UiSubagentNotification
import com.letta.mobile.data.subagents.subagentIdentityOf
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_activity_subagent
import com.letta.mobile.sharedui.resources.rows_copy_transcript_path
import com.letta.mobile.sharedui.resources.rows_hide_details
import com.letta.mobile.sharedui.resources.rows_report_agent
import com.letta.mobile.sharedui.resources.rows_report_completed
import com.letta.mobile.sharedui.resources.rows_report_failed
import com.letta.mobile.sharedui.resources.rows_report_running
import com.letta.mobile.sharedui.resources.rows_report_tokens
import com.letta.mobile.sharedui.resources.rows_report_tools
import com.letta.mobile.sharedui.resources.rows_show_details
import com.letta.mobile.sharedui.resources.rows_tool_card_detail
import com.letta.mobile.sharedui.resources.rows_tool_card_fact_agent_id
import com.letta.mobile.sharedui.resources.rows_tool_card_fact_session_id
import com.letta.mobile.sharedui.resources.rows_tool_card_fact_status
import com.letta.mobile.sharedui.resources.rows_tool_card_fact_subagent_id
import com.letta.mobile.sharedui.resources.rows_tool_card_fact_task_id
import com.letta.mobile.sharedui.resources.rows_tool_card_fact_transcript
import com.letta.mobile.sharedui.resources.rows_tool_card_fact_type
import com.letta.mobile.sharedui.resources.rows_tool_card_section_report
import com.letta.mobile.sharedui.resources.rows_view_conversation
import com.letta.mobile.ui.chat.subagents.ExternalAgentBadge
import com.letta.mobile.ui.components.LettaToolCard
import com.letta.mobile.ui.components.LettaToolCardAction
import com.letta.mobile.ui.components.LettaToolCardFact
import com.letta.mobile.ui.components.LettaToolCardFacts
import com.letta.mobile.ui.components.LettaToolCardHeader
import com.letta.mobile.ui.components.LettaToolCardParts
import com.letta.mobile.ui.components.LettaToolCardSection
import com.letta.mobile.ui.components.LettaToolCardStyle
import com.letta.mobile.ui.components.disclosure.DisclosureRow
import com.letta.mobile.ui.components.foundation.LettaMenuItem
import com.letta.mobile.ui.components.foundation.LettaTonalPill
import com.letta.mobile.ui.components.line.DefaultDisclosureLabels
import com.letta.mobile.ui.components.line.LineDisclosure
import com.letta.mobile.ui.components.line.LineDisclosureLabels
import com.letta.mobile.ui.components.line.StatusTone
import com.letta.mobile.ui.markdown.SharedMarkdownText
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.pill.LettaPillColors
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * A subagent's report (its `<task-notification>`) as the shared [LettaToolCard] line: its state's glyph,
 * "Agent <task> completed", the external agent's badge, and the chevron right after them. Opened: what
 * it used (tools, time, tokens) as chips, "View conversation", the report itself (the runtime's metadata
 * line taken off its top) in a capped section, and behind its own Detail the ids, the task and the
 * transcript path to copy. Nothing raw ("tool_uses: 9", "subagent_id=…") shows unless Detail is opened.
 */
@Composable
internal fun SubagentReportCard(notification: UiSubagentNotification, onOpen: (() -> Unit)?, expansion: LineDisclosure) {
    val (meta, report) = remember(notification.result) { splitReportHeader(notification.result.orEmpty()) }
    val status = notification.status.trim().lowercase()
    val identity = subagentIdentityOf(meta[KEY_TYPE], listOf(notification.subagentAgentId, meta[KEY_AGENT_ID]))
    val word = reportStatusWord(status)
    LettaToolCard(
        header = LettaToolCardHeader(
            label = stringResource(Res.string.rows_report_agent),
            title = notification.summary ?: stringResource(Res.string.rows_activity_subagent),
            tone = reportTone(status),
        ),
        modifier = Modifier.testTag(ChatRowTestTags.SUBAGENT_REPORT_CARD),
        parts = LettaToolCardParts(
            style = LettaToolCardStyle.Line,
            trailing = {
                word?.let { ReportWord(it) }
                ExternalAgentBadge(identity)
            },
            expansion = expansion,
        ),
    ) {
        ReportChips(notification)
        onOpen?.let { LettaToolCardAction(LettaMenuItem(label = stringResource(Res.string.rows_view_conversation), onClick = it)) }
        report.takeIf { it.isNotBlank() }?.let { text ->
            LettaToolCardSection(stringResource(Res.string.rows_tool_card_section_report)) { SharedMarkdownText(text = text) }
        }
        ReportDetails(notification, meta)
    }
}

@Composable
private fun ReportWord(text: String) {
    Text(text = text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
}

/** "9 tools", "1m 1s", "12.4k tokens": what the run used, in words, as chips. */
@Composable
private fun ReportChips(notification: UiSubagentNotification) {
    val usage = remember(notification.usage) { reportUsage(notification.usage) }
    val tools = usage[USAGE_TOOLS]?.toInt()
    val durationMs = notification.durationMs ?: usage[USAGE_DURATION]
    val tokens = usage[USAGE_TOKENS]
    val chips = listOfNotNull(
        tools?.let { pluralStringResource(Res.plurals.rows_report_tools, it, it) },
        durationMs?.let { formatDuration(it) },
        tokens?.let { stringResource(Res.string.rows_report_tokens, compactCount(it)) },
    )
    if (chips.isEmpty()) return
    val colors = LettaPillColors(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurfaceVariant)
    Row(
        modifier = Modifier.testTag(ChatRowTestTags.SUBAGENT_REPORT_CHIPS),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        chips.forEach { LettaTonalPill(it, colors) }
    }
}

/** The ids, the task and the transcript behind their own quiet Detail line, closed by default. */
@Composable
private fun ReportDetails(notification: UiSubagentNotification, meta: Map<String, String>) {
    val facts = listOfNotNull(
        meta[KEY_TYPE]?.let { LettaToolCardFact(stringResource(Res.string.rows_tool_card_fact_type), it) },
        (meta[KEY_STATUS] ?: notification.status.takeIf { it.isNotBlank() })
            ?.let { LettaToolCardFact(stringResource(Res.string.rows_tool_card_fact_status), it) },
        meta[KEY_SUBAGENT_ID]?.let { LettaToolCardFact(stringResource(Res.string.rows_tool_card_fact_subagent_id), it, monospace = true) },
        (meta[KEY_AGENT_ID] ?: notification.subagentAgentId)
            ?.let { LettaToolCardFact(stringResource(Res.string.rows_tool_card_fact_agent_id), it, monospace = true) },
        meta[KEY_SESSION_ID]?.let { LettaToolCardFact(stringResource(Res.string.rows_tool_card_fact_session_id), it, monospace = true) },
        notification.taskId?.let { LettaToolCardFact(stringResource(Res.string.rows_tool_card_fact_task_id), it, monospace = true) },
    )
    val transcript = notification.transcriptUri
    if (facts.isEmpty() && transcript == null) return
    var open by rememberSaveable(notification.taskId, notification.result) { mutableStateOf(false) }
    DisclosureRow(
        disclosure = LineDisclosure(open, ReportDetailLabels) { open = !open },
        modifier = Modifier.testTag(ChatRowTestTags.SUBAGENT_REPORT_DETAIL),
    ) {
        Text(
            text = stringResource(Res.string.rows_tool_card_detail),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (!open) return
    LettaToolCardFacts(facts)
    transcript?.let { path ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f)) {
                val label = stringResource(Res.string.rows_tool_card_fact_transcript)
                LettaToolCardFacts(listOf(LettaToolCardFact(label, path, monospace = true)))
            }
            CopyIconButton(CopyAction(text = path, contentDescription = stringResource(Res.string.rows_copy_transcript_path)))
        }
    }
}

/** The report's state in a word after its title; null for a state the protocol does not name. */
@Composable
private fun reportStatusWord(status: String): String? = when (status) {
    in NOTIFICATION_SUCCESS_STATUSES -> stringResource(Res.string.rows_report_completed)
    in NOTIFICATION_FAILURE_STATUSES -> stringResource(Res.string.rows_report_failed)
    in NOTIFICATION_RUNNING_STATUSES -> stringResource(Res.string.rows_report_running)
    else -> null
}

private fun reportTone(status: String): StatusTone = when (status) {
    in NOTIFICATION_FAILURE_STATUSES -> StatusTone.Failed
    in NOTIFICATION_RUNNING_STATUSES -> StatusTone.Working
    in NOTIFICATION_SUCCESS_STATUSES -> StatusTone.Done
    else -> StatusTone.Info
}

/**
 * The runtime's metadata block at the top of a subagent report (`subagent_type=codex subagent_id=…
 * subagent_status=success agent_id=… runtime_session_id=…`), and the report without it. Only a
 * leading block of lines made entirely of known `key=value` pairs counts; anything else (a report
 * with no such block, a line with one unknown key, prose that merely contains `=`) leaves the report
 * untouched and the metadata empty.
 */
internal fun splitReportHeader(report: String): Pair<Map<String, String>, String> {
    val lines = report.lines()
    val meta = LinkedHashMap<String, String>()
    var index = lines.indexOfFirst { it.isNotBlank() }.coerceAtLeast(0)
    while (index < lines.size) {
        val pairs = metadataPairs(lines[index]) ?: break
        meta.putAll(pairs)
        index++
    }
    if (meta.isEmpty()) return emptyMap<String, String>() to report
    return meta to lines.drop(index).joinToString("\n").trim()
}

/** [line]'s `key=value` pairs when every token is one with a known key; null otherwise. */
private fun metadataPairs(line: String): List<Pair<String, String>>? {
    val tokens = line.trim().split(WHITESPACE).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return null
    val pairs = tokens.map { token ->
        val key = token.substringBefore('=', missingDelimiterValue = "")
        val value = token.substringAfter('=', missingDelimiterValue = "")
        key to value
    }
    return pairs.takeIf { all -> all.all { (key, value) -> key in REPORT_HEADER_KEYS && value.isNotEmpty() } }
}

/** The usage block's numbers by key ("tool_uses: 9", "total_tokens: 12400", "duration_ms: 61000"). */
internal fun reportUsage(usage: String?): Map<String, Long> =
    usage.orEmpty().lines().mapNotNull { line ->
        val key = line.substringBefore(':', missingDelimiterValue = "").trim().lowercase()
        val value = line.substringAfter(':', missingDelimiterValue = "").trim().toLongOrNull()
        if (key.isEmpty() || value == null) null else key to value
    }.toMap()

/** 980, 12.4k, 1.2M. */
private fun compactCount(value: Long): String = when {
    value >= MILLION -> "${(value / (MILLION / TENTHS)) / TENTHS.toDouble()}M"
    value >= THOUSAND -> "${(value / (THOUSAND / TENTHS)) / TENTHS.toDouble()}k"
    else -> value.toString()
}

private val ReportDetailLabels = LineDisclosureLabels(
    Res.string.rows_show_details,
    Res.string.rows_hide_details,
    DefaultDisclosureLabels.expandedState,
    DefaultDisclosureLabels.collapsedState,
)

private const val KEY_TYPE = "subagent_type"
private const val KEY_SUBAGENT_ID = "subagent_id"
private const val KEY_STATUS = "subagent_status"
private const val KEY_AGENT_ID = "agent_id"
private const val KEY_SESSION_ID = "runtime_session_id"
private val REPORT_HEADER_KEYS = setOf(KEY_TYPE, KEY_SUBAGENT_ID, KEY_STATUS, KEY_AGENT_ID, KEY_SESSION_ID)
private const val USAGE_TOOLS = "tool_uses"
private const val USAGE_DURATION = "duration_ms"
private const val USAGE_TOKENS = "total_tokens"
private const val THOUSAND = 1_000L
private const val MILLION = 1_000_000L
private const val TENTHS = 10L
private val WHITESPACE = Regex("\\s+")
