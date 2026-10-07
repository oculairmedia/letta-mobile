package com.letta.mobile.ui.screens.channels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.channel.ChannelsPageActions
import com.letta.mobile.data.channel.ChannelsPageController
import com.letta.mobile.data.channel.ChannelsPageState
import com.letta.mobile.data.session.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Android binding for the shared Channels page (letta-mobile-c3np7.5.7): owns a
 * [ChannelsPageController] over the active session graph - the same port desktop binds - for the
 * ViewModel's lifetime. Search, filter and selection logic all live in sharedLogic.
 */
@HiltViewModel
class ChannelsViewModel @Inject constructor(
    sessionManager: SessionManager,
) : ViewModel() {
    private val controller = ChannelsPageController.forSession(
        sessionGraphProvider = sessionManager,
        scope = viewModelScope,
    )

    val state: StateFlow<ChannelsPageState> = controller.state
    val actions: ChannelsPageActions = controller

    init {
        controller.start()
    }

    override fun onCleared() {
        controller.close()
    }
}
