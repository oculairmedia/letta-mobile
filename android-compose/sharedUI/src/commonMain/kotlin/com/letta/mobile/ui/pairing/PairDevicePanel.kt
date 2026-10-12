package com.letta.mobile.ui.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.letta.mobile.data.pairing.InvitePhase
import com.letta.mobile.data.pairing.InvitePhases
import com.letta.mobile.data.pairing.PairInviteRequest
import com.letta.mobile.data.pairing.PairInviteSession
import com.letta.mobile.data.pairing.PairInviteUiState
import com.letta.mobile.ui.shell.header.HeaderControls
import com.letta.mobile.ui.shell.header.HeaderNav
import com.letta.mobile.ui.shell.header.HeaderTitle
import com.letta.mobile.ui.shell.header.ShellHeader
import com.letta.mobile.ui.shell.scaffold.LettaPageScaffold
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.coroutines.delay

/** Inviter callbacks, grouped so the panel's signature stays small. */
class PairDeviceActions(
    val onMint: (PairInviteRequest) -> Unit,
    val onNewCode: () -> Unit = {},
    /** Back to the scope form (also "Done" after a redemption). */
    val onChangeAccess: () -> Unit = {},
    val onRevokeDevice: (String) -> Unit,
    val onRevokeAllOthers: () -> Unit = {},
    val onRevokeInvite: (String) -> Unit,
    /** The platform share sheet; null where there is none (desktop) — Copy is always offered. */
    val onShareLink: ((String) -> Unit)? = null,
) {
    companion object {
        fun forSession(session: PairInviteSession, onShareLink: ((String) -> Unit)?) = PairDeviceActions(
            onMint = session::mint,
            onNewCode = session::newCode,
            onChangeAccess = session::dismissInvite,
            onRevokeDevice = session::revokeDevice,
            onRevokeAllOthers = session::revokeAllOthers,
            onRevokeInvite = session::revokeInvite,
            onShareLink = onShareLink,
        )
    }
}

/** One inviter render: the session state, the agents the form may offer, and the time it is drawn at. */
class PairDeviceView(val state: PairInviteUiState, val agents: List<AgentChoice>, val nowMs: Long) {
    val phase: InvitePhase get() = InvitePhases.of(state, nowMs)
}

/** What a platform hands the shared inviter: the live session, agent choices, clock, and its share sheet (if any). */
class PairDeviceBinding(
    val session: PairInviteSession,
    val agents: List<AgentChoice>,
    val clock: () -> Long,
    val onShareLink: ((String) -> Unit)?,
)

/**
 * The inviter side of scan-to-connect (letta-mobile-dpv7m.2/.5), shared by the
 * phone and desktop: the invite card (scope -> QR + countdown -> expired /
 * redeemed) above the paired-devices list. Stateless: [PairDeviceView.nowMs] is passed in.
 */
@Composable
fun PairDevicePanel(
    view: PairDeviceView,
    actions: PairDeviceActions,
    modifier: Modifier = Modifier,
) {
    val state = view.state
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.lg)) {
        Text(PairingStrings.INVITE_TITLE, style = MaterialTheme.typography.titleLarge)
        if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(PairingTags.INVITE_ERROR))
        }
        PairInviteCard(view = view, actions = actions)
        HorizontalDivider()
        PairedDevicesList(state = state, actions = actions, nowMs = view.nowMs)
    }
}

/**
 * [PairDevicePanel] bound to a live [PairInviteSession]: ticks the countdown
 * once a second while a code is live and polls the host so the redeeming
 * device shows up. Platforms only build the [PairDeviceBinding].
 */
@Composable
fun PairDeviceSessionPanel(binding: PairDeviceBinding, modifier: Modifier = Modifier) {
    val session = binding.session
    val state by session.state.collectAsState()
    val nowMs = rememberTickingNow(binding.clock, active = state.invite != null)
    LaunchedEffect(session) { session.refresh() }
    LaunchedEffect(state.invite?.inviteId) {
        while (state.invite != null && InvitePhases.of(state, binding.clock()) !is InvitePhase.Expired) {
            delay(INVITE_POLL_INTERVAL_MS)
            session.refresh()
        }
    }
    PairDevicePanel(
        view = PairDeviceView(state, binding.agents, nowMs),
        actions = PairDeviceActions.forSession(session, binding.onShareLink),
        modifier = modifier,
    )
}

/** "Pair a device" as a full page (Android route): header with back, then the session panel. */
@Composable
fun PairDevicePage(binding: PairDeviceBinding, onNavigateBack: () -> Unit, modifier: Modifier = Modifier) {
    LettaPageScaffold(
        modifier = modifier,
        header = {
            ShellHeader(
                title = HeaderTitle.Text(PairingStrings.INVITE_TITLE),
                controls = HeaderControls(nav = HeaderNav.Back(onNavigateBack)),
            )
        },
    ) { padding ->
        PairDeviceSessionPanel(
            binding = binding,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(LettaDimens.Space.xl),
        )
    }
}

private const val INVITE_POLL_INTERVAL_MS = 3_000L
