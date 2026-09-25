package com.letta.mobile.ui.screens.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.repository.modelcontrol.ProviderAdminController
import com.letta.mobile.data.repository.modelcontrol.ProviderConnectionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Provider Admin (letta-mobile-w4q4p): binds the shared
 * [ProviderAdminController] to the view model's lifecycle. All behaviour —
 * listing, the connect form, disconnect confirmation — lives in sharedLogic.
 */
@HiltViewModel
class ProviderAdminViewModel @Inject constructor(
    repository: ProviderConnectionRepository,
) : ViewModel() {
    val controller = ProviderAdminController(viewModelScope, repository)

    init {
        controller.refresh()
    }
}
