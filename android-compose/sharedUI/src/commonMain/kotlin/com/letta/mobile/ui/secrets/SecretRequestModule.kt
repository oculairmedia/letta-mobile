package com.letta.mobile.ui.secrets

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.secrets.client.SecretFormController
import com.letta.mobile.data.secrets.client.SecretRequestClient
import com.letta.mobile.data.secrets.client.SecretRequestScope
import com.letta.mobile.data.secrets.request.SecretRequestRecord
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.secret_form_title
import com.letta.mobile.ui.host.LettaPane
import com.letta.mobile.ui.host.LettaPaneKind
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

private const val MILLIS_PER_MINUTE = 60_000L
private val ExpiryTick = 15.seconds

/** Test tags of the shell's secret request mount. */
object SecretRequestModuleTags {
    /** The secure form's pane. */
    const val FORM_PANE = "secret-request-form-pane"
}

/**
 * What the shell needs to show secret requests: the [client] that lists and answers them, the
 * [agentName] of a requesting agent as the person knows it (null shows the agent id), and the
 * [clock] the expiry countdown reads.
 */
@Immutable
class SecretRequestHost(
    /** Lists, submits and declines requests. */
    val client: SecretRequestClient,
    /** The person-facing name of an agent, or null when the host has none. */
    val agentName: (AgentId) -> String? = { null },
    /** The clock of the expiry countdown. */
    val clock: Clock = Clock.System,
) {
    /** The name to show for [agent]: its name, else its id. */
    fun labelOf(agent: AgentId): String = agentName(agent) ?: agent.value
}

/**
 * Where the shell's secret request card and form get their requests. Null (the default) is a host
 * with no secret request client, where nothing is mounted at all.
 */
val LocalSecretRequestHost = staticCompositionLocalOf<SecretRequestHost?> { null }

/** Lets the shell inside [content] show and answer secret requests through [host]. */
@Composable
fun ProvideSecretRequestHost(host: SecretRequestHost?, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSecretRequestHost provides host, content = content)
}

/** The pending requests a mounted card module shows, and how it opens the form for one. */
@Immutable
class SecretRequestPanel(
    /** The pending requests, soonest expiry first. */
    val pending: List<SecretRequestRecord>,
    /** The host they come from. */
    val host: SecretRequestHost,
    /** Opens the secure form for a request. */
    val open: (SecretRequestRecord) -> Unit,
)

/** The panel a card module reads; provided by [SecretRequestMount] only while a request is pending. */
val LocalSecretRequestPanel = staticCompositionLocalOf<SecretRequestPanel?> { null }

/**
 * Mounts the secret request card module through [card] only while the host lists a pending
 * request, and the secure form for the request the person opened. The form stays up after its
 * request stops being pending (it shows the receipt) until the person closes it. Nothing mounts
 * without a [LocalSecretRequestHost].
 */
@Composable
fun SecretRequestMount(card: @Composable (Modifier) -> Unit) {
    val host = LocalSecretRequestHost.current ?: return
    val pending by remember(host) { host.client.pending(SecretRequestScope.Everyone) }.collectAsState(emptyList())
    var opened by remember(host) { mutableStateOf<SecretRequestRecord?>(null) }
    if (pending.isNotEmpty()) {
        val panel = SecretRequestPanel(pending.sortedBy { it.timing.expiresAtMs }, host) { opened = it }
        CompositionLocalProvider(LocalSecretRequestPanel provides panel) {
            card(Modifier.padding(LettaDimens.Space.sm).widthIn(max = LettaDimens.Pane.noticeMaxWidth))
        }
    }
    opened?.let { request -> SecretRequestFormPane(request, host) { opened = null } }
}

/**
 * The shell module of [com.letta.mobile.data.shell.layout.ShellViews.SECRET_REQUEST]: the card of
 * the pending request that expires first, with how many others wait. Draws nothing outside a
 * [SecretRequestMount].
 */
@Composable
fun SecretRequestModule(modifier: Modifier) {
    val panel = LocalSecretRequestPanel.current ?: return
    val first = panel.pending.firstOrNull() ?: return
    val clock = panel.host.clock
    val now by produceState(clock.now().toEpochMilliseconds(), clock) {
        while (true) {
            delay(ExpiryTick)
            value = clock.now().toEpochMilliseconds()
        }
    }
    val model = SecretRequestCardModel(
        request = first,
        agentLabel = panel.host.labelOf(first.details.requestingAgent),
        minutesLeft = ((first.timing.expiresAtMs - now) / MILLIS_PER_MINUTE).toInt().coerceAtLeast(0),
        othersWaiting = panel.pending.size - 1,
    )
    SecretRequestCard(model, onOpen = { panel.open(first) }, modifier = modifier)
}

/**
 * The secure form for [request] in a dialog pane of its own (never chat content). A form whose
 * request leaves the host's pending list while the person is still answering it expires.
 */
@Composable
private fun SecretRequestFormPane(request: SecretRequestRecord, host: SecretRequestHost, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val controller = remember(request.requestId) { SecretFormController(request, host.client, scope, host.clock) }
    val pending = remember(host) { host.client.pending(SecretRequestScope.Everyone) }
    ExpireWhenGone(controller, pending)
    LettaPane(
        LettaPaneKind.Dialog,
        onDismiss = onClose,
        title = stringResource(Res.string.secret_form_title),
        testTag = SecretRequestModuleTags.FORM_PANE,
    ) {
        SecretRequestForm(
            controller,
            host.labelOf(request.details.requestingAgent),
            onClose,
            Modifier.padding(LettaDimens.Space.xl),
        )
    }
}

@Composable
private fun ExpireWhenGone(controller: SecretFormController, pending: Flow<List<SecretRequestRecord>>) {
    LaunchedEffect(controller, pending) {
        pending.collect { list -> if (list.none { it.requestId == controller.requestId }) controller.expire() }
    }
}
