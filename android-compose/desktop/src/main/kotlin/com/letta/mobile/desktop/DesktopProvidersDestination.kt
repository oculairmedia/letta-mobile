package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.letta.mobile.data.repository.modelcontrol.AdminRpcInvoker
import com.letta.mobile.data.repository.modelcontrol.ModelCatalogRepository
import com.letta.mobile.data.repository.modelcontrol.ProviderConnectionRepository
import com.letta.mobile.data.repository.modelcontrol.ProviderManagementController
import com.letta.mobile.ui.modelcontrol.ProviderManagementActions
import com.letta.mobile.ui.modelcontrol.ProviderManagementPane

/**
 * Providers & Models (letta-mobile-w4q4p.6): the desktop binding of the shared
 * pane. Repositories and the presenter are the sharedLogic ones Android uses;
 * this file only wires them to the desktop's channel transport and a
 * composition scope.
 */
@Composable
internal fun ProvidersDestinationContent(rpc: AdminRpcInvoker, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val controller = remember(rpc) {
        ProviderManagementController(scope, ProviderConnectionRepository(rpc), ModelCatalogRepository(rpc))
    }
    LaunchedEffect(controller) { controller.refresh() }
    val state by controller.state.collectAsState()
    val actions = remember(controller) { ProviderManagementActions.bind(controller) }
    ProviderManagementPane(state = state, actions = actions, modifier = modifier)
}
