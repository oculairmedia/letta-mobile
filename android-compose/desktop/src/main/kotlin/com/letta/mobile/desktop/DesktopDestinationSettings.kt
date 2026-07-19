package com.letta.mobile.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.schedules.CronTask
import com.letta.mobile.data.skills.Skill
import com.letta.mobile.desktop.channels.DesktopChannelLibraryState
import com.letta.mobile.desktop.channels.DesktopChannelLibrarySurface
import com.letta.mobile.desktop.chat.DesktopChatSurfaceState
import com.letta.mobile.desktop.components.DesktopChipTab
import com.letta.mobile.desktop.data.desktopConfigIdFor
import com.letta.mobile.desktop.memory.DesktopBlockApi
import com.letta.mobile.desktop.memory.DesktopMemorySurface
import com.letta.mobile.desktop.memory.DesktopMemorySurfaceState
import com.letta.mobile.desktop.schedules.DesktopScheduleLibraryState
import com.letta.mobile.desktop.schedules.DesktopScheduleSurface
import com.letta.mobile.desktop.skills.DesktopSkillsSurface
import com.letta.mobile.desktop.tools.DesktopToolLibraryState
import org.jetbrains.jewel.ui.component.Text as JewelText
import org.jetbrains.jewel.ui.component.TextField as JewelTextField

@Composable
internal fun BackendCard(config: LettaConfig) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.CloudQueue,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Column {
                    Text(
                        text = "Default backend",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = config.serverUrl,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val pillColors = StatusPillColors(
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    borderColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.24f),
                )
                StatusPill(text = config.mode.label, colors = pillColors)
                StatusPill(text = "Shared model layer", colors = pillColors)
                StatusPill(text = "Windows JVM", colors = pillColors)
            }
        }
    }
}

@Composable
internal fun BackendSettingsCard(
    config: LettaConfig,
    onConfigSaved: (LettaConfig) -> Unit,
    onTokenCleared: () -> Unit,
) {
    var serverUrl by remember(config.id, config.serverUrl) { mutableStateOf(TextFieldValue(config.serverUrl)) }
    var tokenInput by remember(config.id) { mutableStateOf(TextFieldValue("")) }
    var mode by remember(config.id) { mutableStateOf(config.mode) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.54f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Backend",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            BackendServerUrlField(serverUrl = serverUrl, onServerUrlChange = { serverUrl = it })
            BackendModeSelector(mode = mode, onModeChange = { mode = it })
            BackendTokenField(config = config, tokenInput = tokenInput, onTokenInputChange = { tokenInput = it })
            BackendSettingsActions(
                BackendSettingsActionsParams(
                    config = config,
                    serverUrl = serverUrl,
                    tokenInput = tokenInput,
                    mode = mode,
                    onConfigSaved = onConfigSaved,
                    onTokenCleared = onTokenCleared,
                    onTokenInputChange = { tokenInput = it },
                ),
            )
        }
    }
}

@Composable
private fun BackendServerUrlField(
    serverUrl: TextFieldValue,
    onServerUrlChange: (TextFieldValue) -> Unit,
) {
    DesktopSettingsFieldLabel("Server URL")
    JewelTextField(
        value = serverUrl,
        onValueChange = onServerUrlChange,
        placeholder = { JewelText("https://app.letta.com") },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun BackendModeSelector(
    mode: LettaConfig.Mode,
    onModeChange: (LettaConfig.Mode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DesktopSettingsFieldLabel("Mode")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LettaConfig.Mode.entries.forEach { option ->
                DesktopChipTab(
                    text = option.label,
                    active = mode == option,
                    onClick = { onModeChange(option) },
                )
            }
        }
    }
}

@Composable
private fun BackendTokenField(
    config: LettaConfig,
    tokenInput: TextFieldValue,
    onTokenInputChange: (TextFieldValue) -> Unit,
) {
    DesktopSettingsFieldLabel("Access token")
    JewelTextField(
        value = tokenInput,
        onValueChange = onTokenInputChange,
        placeholder = {
            JewelText(if (config.accessToken == null) "Optional" else "Saved token hidden")
        },
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
}

private data class BackendSettingsActionsParams(
    val config: LettaConfig,
    val serverUrl: TextFieldValue,
    val tokenInput: TextFieldValue,
    val mode: LettaConfig.Mode,
    val onConfigSaved: (LettaConfig) -> Unit,
    val onTokenCleared: () -> Unit,
    val onTokenInputChange: (TextFieldValue) -> Unit,
)

@Composable
private fun BackendSettingsActions(params: BackendSettingsActionsParams) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DesktopDefaultButton(
            onClick = {
                val normalizedUrl = params.serverUrl.text.trim()
                params.onConfigSaved(
                    LettaConfig(
                        id = desktopConfigIdFor(normalizedUrl),
                        mode = params.mode,
                        serverUrl = normalizedUrl,
                        accessToken = params.tokenInput.text.trim().takeIf { it.isNotBlank() }
                            ?: params.config.accessToken,
                    ),
                )
                params.onTokenInputChange(TextFieldValue(""))
            },
        ) {
            DesktopButtonContent("Save")
        }
        if (params.config.accessToken != null) {
            DesktopOutlinedButton(
                onClick = {
                    params.onTokenInputChange(TextFieldValue(""))
                    params.onTokenCleared()
                },
            ) {
                DesktopButtonContent("Clear token")
            }
        }
    }
}

@Composable
internal fun DesktopSettingsFieldLabel(text: String) {
    JewelText(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
    )
}

private val LettaConfig.Mode.label: String
    get() = when (this) {
        LettaConfig.Mode.CLOUD -> "Cloud"
        LettaConfig.Mode.SELF_HOSTED -> "Self-hosted"
        LettaConfig.Mode.LOCAL -> "Local runtime"
    }

@Composable
internal fun StartupReadinessCard(featureReadiness: List<DesktopFeatureReadiness>) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.54f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Startup readiness",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )

            featureReadiness.forEach { feature ->
                ReadinessRow(feature)
            }
        }
    }
}

@Composable
internal fun ReadinessRow(feature: DesktopFeatureReadiness) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(10.dp)
                .background(feature.state.color(), MaterialTheme.shapes.small),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.weight(1f),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = feature.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                StatusPill(
                    text = feature.state.label,
                    colors = StatusPillColors(
                        containerColor = feature.state.color().copy(alpha = 0.12f),
                        contentColor = feature.state.color(),
                        borderColor = Color.Transparent,
                    ),
                )
            }
            Text(
                text = feature.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun DesktopFeatureState.color(): Color = when (this) {
    DesktopFeatureState.Ready -> MaterialTheme.colorScheme.primary
    DesktopFeatureState.InProgress -> MaterialTheme.colorScheme.tertiary
    DesktopFeatureState.AndroidOnly -> MaterialTheme.colorScheme.secondary
}

private val DesktopFeatureState.label: String
    get() = when (this) {
        DesktopFeatureState.Ready -> "Ready"
        DesktopFeatureState.InProgress -> "In progress"
        DesktopFeatureState.AndroidOnly -> "Android only"
    }

internal data class StatusPillColors(
    val containerColor: Color = Color.Transparent,
    val contentColor: Color = Color.Unspecified,
    val borderColor: Color = Color.Unspecified,
)

@Composable
internal fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
    colors: StatusPillColors = StatusPillColors(),
) {
    val contentColor = colors.contentColor.takeUnless { it == Color.Unspecified }
        ?: MaterialTheme.colorScheme.onSurfaceVariant
    val borderColor = colors.borderColor.takeUnless { it == Color.Unspecified }
        ?: MaterialTheme.colorScheme.outlineVariant
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = colors.containerColor,
        contentColor = contentColor,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
