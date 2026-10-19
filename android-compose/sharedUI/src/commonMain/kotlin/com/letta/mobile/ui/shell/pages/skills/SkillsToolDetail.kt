package com.letta.mobile.ui.shell.pages.skills

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.letta.mobile.data.model.Tool
import com.letta.mobile.ui.chat.surface.timeline.rows.ToolOutputBlock
import com.letta.mobile.ui.components.LettaPill
import com.letta.mobile.ui.host.InspectorPane
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 *  (hard cut S17): a tool's read-only detail - what it does, its tags and its
 * source or schema. letta-code's tools are code-defined (`tool.list` / `tool.get` only), so there
 * is nothing to edit; the Android tool editor went with the REST backend.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ToolDetailPane(tool: Tool, onClose: () -> Unit) {
    InspectorPane(title = tool.name, onClose = onClose, testTag = SkillsPageTags.TOOL_DETAIL) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(LettaDimens.Space.lg),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm), verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
                listOfNotNull(tool.toolType, tool.sourceType).filter(String::isNotBlank).forEach { LettaPill(it, MaterialTheme.colorScheme.onSurfaceVariant) }
                tool.tags.forEach { LettaPill(it, MaterialTheme.colorScheme.secondary) }
            }
            tool.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            toolSource(tool)?.let { (label, text) ->
                Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                ToolOutputBlock(text)
            }
            Text(READ_ONLY, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** What the detail shows as code: the source when the host sent it, else the call schema. */
internal fun toolSource(tool: Tool): Pair<String, String>? {
    tool.sourceCode?.takeIf { it.isNotBlank() }?.let { return SOURCE to it }
    return tool.jsonSchema?.let { SCHEMA to it.pretty() }
}

private fun JsonObject.pretty(): String = PrettyJson.encodeToString(JsonObject.serializer(), this)

private val PrettyJson = Json { prettyPrint = true }
private const val SOURCE = "Source"
private const val SCHEMA = "Schema"
private const val READ_ONLY = "Tools are part of letta-code; they are read-only here."
