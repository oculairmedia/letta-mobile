package com.letta.mobile.feature.editagent

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.components.CardGroup
import com.letta.mobile.ui.icons.LettaIcons

/**
 * Stable LazyColumn keys for each section sticky header. Used both as item
 * keys and as scroll targets from the title-tap index sheet.
 */
internal object SectionAnchors {
    const val BASICS: String = "section_header_basics"
    const val MODELS: String = "section_header_models"
    const val MEMORY: String = "section_header_memory"
    const val TOOLS: String = "section_header_tools"
    const val RUNTIME: String = "section_header_runtime"
    const val ADVANCED: String = "section_header_advanced"
    const val DANGER: String = "section_header_danger"
}

@Composable
internal fun EditAgentSectionHeader(
    title: String,
    isDanger: Boolean = false,
) {
    val colors = sectionHeaderColors(isDanger)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = colors.container,
        contentColor = colors.content,
        tonalElevation = 1.dp,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

private data class SectionHeaderColors(
    val container: androidx.compose.ui.graphics.Color,
    val content: androidx.compose.ui.graphics.Color,
)

@Composable
private fun sectionHeaderColors(isDanger: Boolean): SectionHeaderColors {
    val scheme = MaterialTheme.colorScheme
    return if (isDanger) {
        SectionHeaderColors(scheme.errorContainer, scheme.onErrorContainer)
    } else {
        SectionHeaderColors(scheme.surfaceContainerHigh, scheme.onSurface)
    }
}

@Composable
internal fun DangerZoneSection(
    onResetMessages: () -> Unit,
    onDeleteAgent: () -> Unit,
) {
    Spacer(modifier = Modifier.height(24.dp))
    CardGroup(
        title = {
            Text(
                text = stringResource(R.string.screen_create_project_danger_zone_title),
                color = MaterialTheme.colorScheme.error,
            )
        },
    ) {
        item(
            headlineContent = {
                OutlinedButton(
                    onClick = onResetMessages,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                ) {
                    Icon(
                        LettaIcons.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.action_reset_messages))
                }
            },
        )
        item(
            headlineContent = {
                Button(
                    onClick = onDeleteAgent,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Icon(
                        LettaIcons.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.screen_agents_dialog_delete_title))
                }
            },
        )
    }
}
