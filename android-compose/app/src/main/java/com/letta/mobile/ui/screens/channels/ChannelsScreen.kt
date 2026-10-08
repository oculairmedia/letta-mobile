package com.letta.mobile.ui.screens.channels

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.oculair.meridian.R
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.shell.pages.channels.ChannelsPage
import com.letta.mobile.ui.shell.pages.channels.ChannelsPageOptions
import com.letta.mobile.ui.theme.LettaTopBarDefaults

/** The phone's page options: the top bar titles it, controls are touch-sized and a tap opens a channel. */
private val AndroidChannelsPageOptions = ChannelsPageOptions(showTitle = false, touch = true, showDetails = true)

/**
 * Android host for the shared [ChannelsPage]: top bar and back only. The open channel's detail is
 * a bottom sheet, which handles back itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelsScreen(
    onNavigateBack: () -> Unit,
    viewModel: ChannelsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = LettaTopBarDefaults.scaffoldContainerColor(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_channels_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(LettaIcons.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                colors = LettaTopBarDefaults.topAppBarColors(),
            )
        },
    ) { paddingValues ->
        ChannelsPage(
            state = state,
            actions = viewModel.actions,
            options = AndroidChannelsPageOptions,
            modifier = Modifier.padding(paddingValues).consumeWindowInsets(paddingValues),
        )
    }
}
