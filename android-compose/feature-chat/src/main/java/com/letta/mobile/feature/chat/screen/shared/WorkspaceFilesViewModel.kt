package com.letta.mobile.feature.chat.screen.shared

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.composer.AgentMentions
import com.letta.mobile.data.composer.Mentionable
import com.letta.mobile.data.composer.MentionableAgent
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.workspace.FileMentionController
import com.letta.mobile.data.workspace.MentionDraft
import com.letta.mobile.data.workspace.WorkspaceFileSource
import com.letta.mobile.data.workspace.WorkspaceFileViewerController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * letta-mobile-bzvro.37: Android binding for workspace files in the shared chat page — the read-only
 * viewer tool cards open, and the composer's `@` file suggestions — over the Hilt-bound
 * [WorkspaceFileSource] (the Iroh host's relay). Both controllers are the ones desktop binds.
 * letta-mobile-zxk2n.6: the `@` suggestions also offer the other agents ([AgentMentions]).
 */
@HiltViewModel
class WorkspaceFilesViewModel @Inject constructor(
    source: WorkspaceFileSource,
    private val agentRepository: IAgentRepository,
) : ViewModel() {
    val viewer = WorkspaceFileViewerController(source, viewModelScope)
    private val mentions = FileMentionController(source, viewModelScope)

    /** File suggestions for the composer's current draft. */
    val mentionables: Flow<List<Mentionable>> = mentions.state.map { it.results }

    /** File suggestions plus every agent except [currentAgentId], the conversation's own. */
    fun mentionablesFor(currentAgentId: String?): Flow<List<Mentionable>> =
        combine(mentionables, agentRepository.agents) { files, agents ->
            files + AgentMentions.mentionables(agents.map { MentionableAgent(it.id.value, it.name) }, excludeAgentId = currentAgentId)
        }

    /** The phone knows no working directory: the host's own directory is searched. */
    fun onDraftChanged(text: String) = mentions.onDraftChanged(MentionDraft(text, cwd = null))

    override fun onCleared() {
        mentions.close()
        viewer.close()
    }
}
