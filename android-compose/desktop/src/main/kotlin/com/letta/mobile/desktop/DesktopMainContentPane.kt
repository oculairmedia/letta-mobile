package com.letta.mobile.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentStatus
import com.letta.mobile.data.repository.SubagentRepository
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.desktop.agent.DesktopEditAgentSurface
import com.letta.mobile.desktop.chat.ChatDetailPane
import com.letta.mobile.desktop.chat.ChatDetailPaneActions
import com.letta.mobile.desktop.chat.ChatDetailPaneState
import com.letta.mobile.desktop.chat.DesktopBackgroundTasksToggle
import com.letta.mobile.desktop.memory.DesktopBlockApi
import kotlinx.coroutines.CoroutineScope

internal data class DesktopMainContentInputs(
    val editingAgentId: String?,
    val selectedDestination: DesktopDestination,
    val modelOptions: List<Pair<String, String>>,
    val agentRepository: IAgentRepository,
    val blockApi: DesktopBlockApi?,
    val secureSettingsStore: SecureSettingsStore,
    val chatScope: CoroutineScope,
    val chatDetailState: ChatDetailPaneState,
    val destinationInputs: DestinationContentInputs,
    val showBackgroundTasks: Boolean,
    val subagentRepository: SubagentRepository?,
    val activeSubagents: List<SubagentEntry>,
)

internal data class DesktopMainContentActions(
    val onEditAgentClose: () -> Unit,
    val onEditAgentSaved: (Int, Boolean) -> Unit,
    val chatDetailActions: ChatDetailPaneActions,
    val destinationActions: DestinationContentActions,
    val onShowBackgroundTasks: () -> Unit,
)

@Composable
internal fun DesktopMainContentPane(
    inputs: DesktopMainContentInputs,
    actions: DesktopMainContentActions,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        val editing = inputs.editingAgentId
        if (editing != null) {
            DesktopEditAgentSurface(
                agentId = editing,
                modelOptions = inputs.modelOptions,
                agentRepository = inputs.agentRepository,
                blockApi = inputs.blockApi,
                settings = inputs.secureSettingsStore,
                scope = inputs.chatScope,
                onClose = actions.onEditAgentClose,
                onSaved = actions.onEditAgentSaved,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (inputs.selectedDestination == DesktopDestination.Conversations) {
            ChatDetailPane(
                state = inputs.chatDetailState,
                actions = actions.chatDetailActions,
                modifier = Modifier.fillMaxSize(),
            )
            if (!inputs.showBackgroundTasks && inputs.subagentRepository != null) {
                DesktopBackgroundTasksToggle(
                    runningCount = inputs.activeSubagents.count { it.status == SubagentStatus.RUNNING },
                    onClick = actions.onShowBackgroundTasks,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 12.dp, end = 16.dp),
                )
            }
        } else {
            DestinationContent(
                destination = inputs.selectedDestination,
                inputs = inputs.destinationInputs,
                actions = actions.destinationActions,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
