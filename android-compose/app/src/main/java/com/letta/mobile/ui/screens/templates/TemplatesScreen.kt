package com.letta.mobile.ui.screens.templates

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.oculair.meridian.R
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.ui.shell.ShellAdminPages
import com.letta.mobile.ui.shell.ShellPage
import com.letta.mobile.ui.shell.ShellPageDeps
import com.letta.mobile.ui.shell.header.HeaderControls
import com.letta.mobile.ui.shell.header.HeaderNav
import com.letta.mobile.ui.shell.header.HeaderTitle
import com.letta.mobile.ui.shell.header.ShellHeader
import com.letta.mobile.ui.shell.pages.templates.templatesPageActions
import com.letta.mobile.ui.shell.scaffold.LettaPageScaffold
import com.letta.mobile.ui.shell.shellTemplatesPage
import com.letta.mobile.ui.theme.LettaTopBarDefaults

/**
 * Android host for the shared Templates page (letta-mobile-ckmub.2.5): top bar and back only. A
 * made agent opens in its chat.
 */
@Composable
fun TemplatesScreen(
    onNavigateBack: () -> Unit,
    onNavigateToAgent: (agentId: String) -> Unit,
    viewModel: TemplatesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = remember(viewModel, onNavigateToAgent) { viewModel.controller.templatesPageActions(onNavigateToAgent) }
    LettaPageScaffold(
        containerColor = LettaTopBarDefaults.scaffoldContainerColor(),
        header = {
            ShellHeader(
                title = HeaderTitle.Text(stringResource(R.string.screen_templates_title)),
                controls = HeaderControls(nav = HeaderNav.Back(onNavigateBack, stringResource(R.string.action_back))),
            )
        },
    ) { paddingValues ->
        val page = ShellAdminPages(templates = shellTemplatesPage(state, actions))
        ShellPage(ShellDestination.Templates, ShellPageDeps(admin = page), Modifier.padding(paddingValues))
    }
}
