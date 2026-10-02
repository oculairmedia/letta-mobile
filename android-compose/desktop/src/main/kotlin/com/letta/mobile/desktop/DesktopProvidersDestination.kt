package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.letta.mobile.data.repository.modelcontrol.ModelControlSession
import com.letta.mobile.ui.modelcontrol.ProviderSettingsActions
import com.letta.mobile.ui.modelcontrol.ProviderSettingsPage
import com.letta.mobile.ui.modelcontrol.ProviderSettingsPane

/**
 * Providers (letta-mobile-w4q4p.6 / .6.1): the desktop binding of the shared
 * settings pane — Accounts, API keys, Custom Endpoints and Models in its own
 * left navigation. The presenter and repositories are the sharedLogic ones
 * Android uses; [session] is the one the model picker and the Models sheet
 * read, so a change here shows there at once.
 */
@Composable
internal fun ProvidersDestinationContent(session: ModelControlSession, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val controller = remember(session) { session.managementController(scope) }
    LaunchedEffect(controller) { controller.refresh() }
    val state by controller.state.collectAsState()
    val actions = remember(controller) { ProviderSettingsActions.bind(controller) }
    var page by remember { mutableStateOf(ProviderSettingsPage.ACCOUNTS) }
    ProviderSettingsPane(state = state, actions = actions, page = page, onPageChange = { page = it }, modifier = modifier)
}
