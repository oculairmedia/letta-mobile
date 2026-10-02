package com.letta.mobile.ui.screens.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.repository.modelcontrol.ModelCatalogRepository
import com.letta.mobile.data.repository.modelcontrol.ProviderConnectionRepository
import com.letta.mobile.data.repository.modelcontrol.ProviderManagementController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Providers & Models (letta-mobile-w4q4p.6): binds the shared
 * [ProviderManagementController] to the view model's lifecycle. All behaviour
 * — listing, connect form, disconnect confirmation, model visibility — lives
 * in sharedLogic; the catalog repository is the one the chat picker reads, so
 * a toggle here is visible there at once.
 */
@HiltViewModel
class ProviderAdminViewModel @Inject constructor(
    providers: ProviderConnectionRepository,
    catalog: ModelCatalogRepository,
) : ViewModel() {
    val controller = ProviderManagementController(viewModelScope, providers, catalog)

    init {
        controller.refresh()
    }
}
