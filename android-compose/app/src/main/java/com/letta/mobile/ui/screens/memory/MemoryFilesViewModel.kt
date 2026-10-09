package com.letta.mobile.ui.screens.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.memory.memfs.MemfsPageController
import com.letta.mobile.data.memory.memfs.MemfsPageState
import com.letta.mobile.data.memory.memfs.MemfsSource
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * letta-mobile-bzvro.37: Android binding for the shared MemFS browser (the Memory screen's Files
 * view): owns a [MemfsPageController] over the Hilt-bound [MemfsSource], the same controller desktop
 * binds, for the ViewModel's lifetime. Listing, history, diffs and the editor live in sharedLogic.
 */
@HiltViewModel
class MemoryFilesViewModel @Inject constructor(
    source: MemfsSource,
) : ViewModel() {
    val controller = MemfsPageController(source, viewModelScope)

    val state: StateFlow<MemfsPageState> = controller.state

    init {
        controller.start()
    }

    /** Follows the agent chosen in the memory graph's agent picker. */
    fun selectAgent(agentId: String?) = controller.selectAgent(agentId)

    override fun onCleared() {
        controller.close()
    }
}
