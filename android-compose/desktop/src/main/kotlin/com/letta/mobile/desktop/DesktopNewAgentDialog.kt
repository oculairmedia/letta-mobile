package com.letta.mobile.desktop

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Build
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.composer.MentionKind
import com.letta.mobile.data.composer.Mentionable
import com.letta.mobile.data.lens.LensDestination
import com.letta.mobile.data.memory.MemoryParityItem
import com.letta.mobile.data.lens.WorkPlayLens
import com.letta.mobile.data.lens.WorkPlayMode
import com.letta.mobile.data.chat.runtime.groupSubagentConversations
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.onboarding.OnboardingTaskKind
import com.letta.mobile.data.model.SubagentStatus
import com.letta.mobile.data.repository.SubagentRepository
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.iroh.IrohAdminRpcAgentDirectory
import com.letta.mobile.data.repository.iroh.IrohAdminRpcChatGateway
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import com.letta.mobile.data.transport.iroh.IrohConnectConfig
import kotlinx.coroutines.CoroutineScope
import com.letta.mobile.desktop.channels.DesktopChannelLibraryController
import com.letta.mobile.desktop.channels.DesktopChannelLibraryState
import com.letta.mobile.desktop.channels.DesktopChannelLibrarySurface
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.letta.mobile.desktop.components.DesktopChipTab
import com.letta.mobile.avatar.core.AvatarActivity
import com.letta.mobile.desktop.avatar.DesktopAvatarCompanion
import com.letta.mobile.desktop.avatar.DesktopAvatarLibraryWindow
import com.letta.mobile.desktop.avatar.defaultAvatarCatalogDir
import com.letta.mobile.desktop.chat.AgentOrb
import com.letta.mobile.desktop.components.DesktopChipTab
import com.letta.mobile.desktop.chat.ChatDetailPane
import com.letta.mobile.desktop.chat.ConversationArchiveFilter
import com.letta.mobile.desktop.chat.createDefaultDesktopChatGateway
import com.letta.mobile.data.search.PaletteItem
import com.letta.mobile.data.search.PaletteItemKind
import com.letta.mobile.desktop.chat.DesktopBackgroundTasksPanel
import com.letta.mobile.desktop.chat.DesktopBackgroundTasksToggle
import com.letta.mobile.desktop.chat.DesktopCommandPalette
import com.letta.mobile.desktop.chat.DesktopModelPickerSheet
import com.letta.mobile.desktop.data.DesktopWsChannelTransport
import com.letta.mobile.desktop.chat.ComposerCommand
import com.letta.mobile.desktop.chat.DesktopChatController
import com.letta.mobile.desktop.chat.DesktopChatSurfaceState
import com.letta.mobile.desktop.chat.DesktopConversationSummary
import com.letta.mobile.desktop.chat.DesktopImageAttachmentLoader
import com.letta.mobile.desktop.data.DesktopFileSecureSettingsStore
import com.letta.mobile.desktop.agent.DesktopEditAgentSurface
import com.letta.mobile.desktop.agent.agentAvatarStyleKey
import com.letta.mobile.desktop.data.DesktopDataBindings
import com.letta.mobile.desktop.data.DesktopLettaConfigStore
import com.letta.mobile.desktop.data.DesktopSessionGraphProvider
import com.letta.mobile.desktop.data.createDefaultDesktopDataBindings
import com.letta.mobile.desktop.data.desktopConfigIdFor
import com.letta.mobile.desktop.memory.DesktopMemoryController
import com.letta.mobile.desktop.memory.DesktopBlockApi
import com.letta.mobile.desktop.memory.DesktopHttpBlockApi
import com.letta.mobile.desktop.memory.DesktopIrohBlockApi
import com.letta.mobile.desktop.memory.DesktopMemorySurface
import com.letta.mobile.desktop.memory.DesktopMemorySurfaceState
import com.letta.mobile.data.schedules.CronApi
import com.letta.mobile.data.schedules.CronTask
import com.letta.mobile.desktop.schedules.DesktopScheduleLibraryController
import com.letta.mobile.desktop.schedules.DesktopScheduleLibraryState
import com.letta.mobile.desktop.schedules.DesktopScheduleSurface
import com.letta.mobile.desktop.tools.DesktopToolLibraryController
import com.letta.mobile.desktop.tools.DesktopToolLibraryState
import com.letta.mobile.data.commands.AgentSlashCommand
import com.letta.mobile.data.skills.Skill
import com.letta.mobile.data.skills.SkillApi
import com.letta.mobile.data.skills.SkillsApi
import com.letta.mobile.desktop.chat.createDesktopLettaHttpClient
import com.letta.mobile.desktop.skills.DesktopSkillsSurface
import com.letta.mobile.desktop.skills.DesktopIrohSkillsApi
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jetbrains.jewel.ui.component.PopupMenu as JewelPopupMenu
import org.jetbrains.jewel.ui.component.Icon as JewelIcon
import org.jetbrains.jewel.ui.component.SimpleListItem as JewelSimpleListItem
import org.jetbrains.jewel.ui.component.Text as JewelText
import org.jetbrains.jewel.ui.component.TextField as JewelTextField

@Composable
internal fun NewAgentDialog(
    modelOptions: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onCreate: (name: String, model: String?) -> Unit,
) {
    var name by remember { mutableStateOf(TextFieldValue("New agent")) }
    var modelValue by remember { mutableStateOf<String?>(null) }
    var modelMenuOpen by remember { mutableStateOf(false) }
    val modelLabel = modelOptions.firstOrNull { it.second == modelValue }?.first ?: "Same as current"
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.width(420.dp).clickable(enabled = false) {},
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "New agent",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Name",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                JewelTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Model",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box {
                    Surface(
                        onClick = { modelMenuOpen = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(modelLabel, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Icon(Icons.Outlined.KeyboardArrowDown, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (modelMenuOpen) {
                        JewelPopupMenu(
                            onDismissRequest = { modelMenuOpen = false; true },
                            horizontalAlignment = Alignment.Start,
                        ) {
                            selectableItem(selected = modelValue == null, onClick = { modelMenuOpen = false; modelValue = null }) {
                                DesktopControlText("Same as current")
                            }
                            modelOptions.forEach { (label, value) ->
                                selectableItem(selected = modelValue == value, onClick = { modelMenuOpen = false; modelValue = value }) {
                                    DesktopControlText(label)
                                }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                ) {
                    DesktopOutlinedButton(onClick = onDismiss) { DesktopButtonContent("Cancel") }
                    DesktopDefaultButton(onClick = { onCreate(name.text.trim(), modelValue) }) {
                        DesktopButtonContent("Create agent")
                    }
                }
            }
        }
    }
}

/**
 * Sort key for a conversation's relative-time label. Real ISO timestamps sort by
 * recency; the local "Queued" placeholder floats to the top (it's a just-sent
 * message), while any other unparseable label (e.g. "Remote" — a conversation
 * with no activity timestamp at all) sorts to the bottom rather than being
 * treated as newest, since the stores already return rows in recency order.
 */
