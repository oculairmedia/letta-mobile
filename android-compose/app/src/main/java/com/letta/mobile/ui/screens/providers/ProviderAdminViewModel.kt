package com.letta.mobile.ui.screens.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.repository.modelcontrol.ModelCatalogRepository
import com.letta.mobile.data.repository.modelcontrol.ProviderConnectionRepository
import com.letta.mobile.data.repository.modelcontrol.ProviderManagementController
import com.letta.mobile.data.repository.modelcontrol.defaultProviderOAuthHttp
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
    /** letta-mobile-zxk2n.3: subscription accounts sign in from the app over the shared sign-in client. */
    val controller = ProviderManagementController(viewModelScope, providers, catalog, defaultProviderOAuthHttp())

    init {
        controller.refresh()
    }
}
