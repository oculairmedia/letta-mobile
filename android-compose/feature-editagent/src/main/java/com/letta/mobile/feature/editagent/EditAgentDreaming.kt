package com.letta.mobile.feature.editagent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.compaction.ConversationCompactRpc
import com.letta.mobile.data.dreaming.AdminRpcDreamingRepository
import com.letta.mobile.data.dreaming.DreamHistorySource
import com.letta.mobile.data.dreaming.DreamTarget
import com.letta.mobile.data.dreaming.DreamingController
import com.letta.mobile.data.memory.memfs.MemfsSource
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.repository.api.ISubagentRepository
import com.letta.mobile.data.repository.api.SubagentParentScope
import com.letta.mobile.data.repository.modelcontrol.AdminRpcInvoker
import com.letta.mobile.ui.dreaming.DreamingPanelHost
import com.letta.mobile.ui.dreaming.DreamingStrings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * letta-mobile-iczm7: the agent's Dreaming surface on the phone. Android only binds: dreaming
 * through the Iroh host's `reflection.*` (the session's admin_rpc), the dream log from the MemFS
 * relay, and live runs from the subagent registry. A session without admin_rpc (the on-phone
 * runtime, a plain HTTP server) answers unsupported, and the shared panel says why.
 */
@HiltViewModel
internal class EditAgentDreamingViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    rpc: AdminRpcInvoker,
    memfs: MemfsSource,
    subagents: ISubagentRepository,
) : ViewModel() {
    val target = DreamTarget(AgentId(requireNotNull(savedStateHandle.get<String>("agentId")) { "Missing agentId" }))

    val controller = DreamingController(
        repository = AdminRpcDreamingRepository(rpc),
        history = DreamHistorySource(memfs),
        scope = viewModelScope,
        activity = { id -> subagents.activeSubagentsFlow(SubagentParentScope(id.value, ConversationCompactRpc.DEFAULT_CONVERSATION)) },
    )

    override fun onCleared() {
        controller.close()
    }
}

/** The Memory section's Dreaming row; empty outside the Hilt-bound screen (previews, tests). */
internal val LocalEditAgentDreamingEntry = compositionLocalOf<@Composable () -> Unit> { {} }

internal object EditAgentDreamingTags {
    const val ENTRY = "edit_agent_dreaming_entry"
}

/** A row that opens the shared Dreaming panel in a bottom sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditAgentDreamingEntry(viewModel: EditAgentDreamingViewModel) {
    var open by rememberSaveable { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(DreamingStrings.TITLE) },
        supportingContent = { Text(DreamingStrings.INTRO) },
        modifier = Modifier.fillMaxWidth().clickable { open = true }.testTag(EditAgentDreamingTags.ENTRY),
    )
    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }) {
            DreamingPanelHost(controller = viewModel.controller, target = viewModel.target)
        }
    }
}
