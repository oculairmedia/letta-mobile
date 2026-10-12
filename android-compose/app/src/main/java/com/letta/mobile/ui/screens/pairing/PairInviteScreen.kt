package com.letta.mobile.ui.screens.pairing

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.oculair.meridian.R
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.pairing.PairDeviceActions
import com.letta.mobile.ui.pairing.PairDevicePanel
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

/**
 * "Pair a device" (letta-mobile-dpv7m.2): the shared inviter panel, bound to
 * the host this phone is connected to. While a code is on screen the screen
 * polls the host so the new device shows up as accepted, and ticks the expiry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairInviteScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PairInviteViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val agents by viewModel.agents.collectAsStateWithLifecycle()
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(state.invite?.inviteId) {
        if (state.invite == null) return@LaunchedEffect
        while (true) {
            delay(POLL_INTERVAL)
            nowMs = System.currentTimeMillis()
            viewModel.refresh()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_pairing_invite_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(LettaIcons.ArrowBack, stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        PairDevicePanel(
            state = state,
            agents = agents,
            actions = PairDeviceActions(
                onMint = viewModel::mint,
                onRevokeDevice = viewModel::revokeDevice,
                onRevokeInvite = viewModel::revokeInvite,
                onDismissInvite = viewModel::dismissInvite,
            ),
            nowMs = nowMs,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(LettaDimens.Space.xl),
        )
    }
}

private val POLL_INTERVAL = 3.seconds
