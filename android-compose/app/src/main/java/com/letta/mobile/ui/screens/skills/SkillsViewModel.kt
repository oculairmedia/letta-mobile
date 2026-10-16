package com.letta.mobile.ui.screens.skills

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.skills.SkillsCatalogController
import com.letta.mobile.data.skills.SkillsCatalogState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Android binding for the skills half of the shared Skills page (letta-mobile-zxk2n.32): one
 * [SkillsCatalogController] for the route's agent (`SkillsRoute.agentId`; the standalone Tools
 * route has none, so its skills are read-only). The tools half is [AllToolsViewModel]'s library.
 */
@HiltViewModel
class SkillsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    skillsApi: SessionSkillsApi,
) : ViewModel() {
    private val controller = SkillsCatalogController(
        api = skillsApi,
        scope = viewModelScope,
        agentId = savedStateHandle.get<String>(AGENT_ID_KEY)?.takeIf { it.isNotBlank() },
    )

    val state: StateFlow<SkillsCatalogState> = controller.state

    init {
        controller.reload()
    }

    fun refresh() = controller.reload()

    fun install(skillName: String) = controller.install(skillName)

    fun uninstall(skillName: String) = controller.uninstall(skillName)

    fun dismissActionError() = controller.dismissActionError()

    override fun onCleared() {
        controller.close()
    }

    private companion object {
        /** `SkillsRoute.agentId`, as type-safe navigation stores it. */
        const val AGENT_ID_KEY = "agentId"
    }
}
