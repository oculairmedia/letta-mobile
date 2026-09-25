package com.letta.mobile.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.letta.mobile.data.repository.modelcontrol.AdminRpcInvoker
import com.letta.mobile.data.repository.modelcontrol.ModelCatalogRepository
import com.letta.mobile.data.repository.modelcontrol.ModelExposureController
import com.letta.mobile.data.repository.modelcontrol.ProviderAdminController
import com.letta.mobile.data.repository.modelcontrol.ProviderConnectionRepository
import com.letta.mobile.ui.modelcontrol.ModelExposurePane
import com.letta.mobile.ui.modelcontrol.ProviderConnectionPane
import com.letta.mobile.ui.modelcontrol.ProviderFormActions
import com.letta.mobile.ui.modelcontrol.ProviderPaneActions

/**
 * Providers & Models (letta-mobile-w4q4p): the desktop binding of the shared
 * provider-management and model-exposure panes. Repositories and presenters
 * are the sharedLogic ones Android uses; this file only wires them to the
 * desktop's channel transport and a composition scope.
 */
@Composable
internal fun ProvidersDestinationContent(rpc: AdminRpcInvoker, modifier: Modifier = Modifier) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(modifier = modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Providers") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Models") })
        }
        if (tab == 0) ProvidersTab(rpc) else ModelsTab(rpc)
    }
}

@Composable
private fun ProvidersTab(rpc: AdminRpcInvoker) {
    val scope = rememberCoroutineScope()
    val controller = remember(rpc) { ProviderAdminController(scope, ProviderConnectionRepository(rpc)) }
    LaunchedEffect(controller) { controller.refresh() }
    val state by controller.state.collectAsState()
    ProviderConnectionPane(
        state = state,
        actions = ProviderPaneActions(
            onConnect = controller::openConnect,
            onDisconnect = controller::requestDisconnect,
            form = ProviderFormActions(
                onChange = controller::updateForm,
                onSubmit = controller::submitConnect,
                onDismiss = controller::dismissForm,
            ),
            onConfirmDisconnect = controller::confirmDisconnect,
            onDismissDisconnect = controller::dismissDisconnect,
        ),
    )
}

@Composable
private fun ModelsTab(rpc: AdminRpcInvoker) {
    val scope = rememberCoroutineScope()
    val controller = remember(rpc) { ModelExposureController(scope, ModelCatalogRepository(rpc)) }
    LaunchedEffect(controller) { controller.refresh() }
    val state by controller.state.collectAsState()
    ModelExposurePane(
        state = state,
        actions = com.letta.mobile.ui.modelcontrol.ModelExposureActions(
            onQueryChange = controller::setQuery,
            onExposedChange = controller::setExposed,
        ),
    )
}
