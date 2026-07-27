package com.letta.mobile.desktop.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.UiGeneratedComponent
import com.letta.mobile.desktop.chat.AgentOrb
import com.letta.mobile.desktop.formatRelativeTimestamp

/** Read-only inputs for [DesktopHomeSurface]. */
@Immutable
data class DesktopHomeState(
    val overview: FleetOverview,
    val sort: FleetSort,
    /** Orb colour index per agent (editor override, else rail position). */
    val orbIndexByAgentId: Map<String, Int>,
    /** Placeholder for the page's chatbox, supplied by the shell's lens. */
    val composerPlaceholder: String = "Message your agent",
)

/** Callbacks out of the Home page. */
@Immutable
data class DesktopHomeActions(
    val onSortKeySelected: (FleetSortKey) -> Unit,
    val onOpenAgent: (String) -> Unit,
    /** Open a conversation by id through the shell's normal selection pathway. */
    val onOpenConversation: (String) -> Unit,
    /** Send this text into the shell's chat pipeline (see the shell's wiring). */
    val onSubmitPrompt: (String) -> Unit,
)

/**
 * Home: what the fleet has been doing, newest first.
 *
 * The page leads with a chatbox and the fleet-wide recent-conversations list —
 * the two things a user actually arrives wanting — and demotes the fleet
 * dashboard (stat tiles + sortable agent table) to the bottom of the same
 * scrolling page, where it works as reference rather than as the headline.
 *
 * ## Rendering seam (Letta Code mods)
 * This composable is the *default* rendering of [FleetOverview], not the
 * definition of the page. Everything shown is derived by [buildFleetOverview]
 * into a plain, renderer-agnostic model; this function only maps that model to
 * Compose.
 *
 * The intended end state is that the page can be **expressed at runtime by a
 * Letta Code mod** (`~/.letta/mods/`): a mod-registered tool emits an A2UI
 * document describing the homepage, and this surface renders it instead of the
 * native dashboard — letting an agent restructure its own homepage with a
 * `/reload` rather than an app release. (Mod-owned terminal panels cannot paint
 * Compose directly, so an A2UI document is the only viable hand-off.)
 *
 * [document] is that seam. It is currently **accepted and ignored**: the
 * desktop module has no A2UI renderer wired up at all — `GeneratedUiCard` in
 * `chat/DesktopChatToolCards.kt` prints `propsJson` as text rather than calling
 * `A2uiSurfaceRenderer`, which today lives in the Android-only `designsystem`
 * module and only composes inside the chat surface stack. Rendering a
 * mod-authored homepage needs three things this repo does not have yet:
 * a KMP/desktop A2UI renderer, a standalone (non-chat) A2UI surface host, and
 * a transport for "home document" envelopes outside a chat message. Until then
 * the native page below is always what draws, and nothing above this function
 * knows the difference.
 */
@Composable
fun DesktopHomeSurface(
    state: DesktopHomeState,
    actions: DesktopHomeActions,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") document: UiGeneratedComponent? = null,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 20.dp),
    ) {
        item { HomeHeader(state.overview.summary) }
        item {
            HomeComposer(
                placeholder = state.composerPlaceholder,
                onSubmit = actions.onSubmitPrompt,
            )
        }
        item { HomeSectionLabel("Recent conversations") }
        val recent = state.overview.recent
        if (recent.isEmpty()) {
            item { HomeEmptyLine("No conversations yet. Start one from the chatbox above.") }
        }
        items(items = recent, key = { it.conversationId }) { conversation ->
            RecentConversationRow(
                conversation = conversation,
                orbIndex = conversation.agentId?.let { state.orbIndexByAgentId[it] } ?: 0,
                onClick = { actions.onOpenConversation(conversation.conversationId) },
            )
        }
        // Reference material, deliberately below the fold.
        fleetDashboardSection(
            state = state,
            onSortKeySelected = actions.onSortKeySelected,
            onOpenAgent = actions.onOpenAgent,
        )
    }
}

@Composable
private fun HomeHeader(summary: FleetSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Home",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = fleetSubtitle(summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Run state is already spelled out by the RUNNING NOW tile, so the subtitle
 * stays to the two counts the tiles do not repeat in words.
 */
internal fun fleetSubtitle(summary: FleetSummary): String =
    "${summary.totalAgents} agents · ${summary.totalConversations} conversations"

@Composable
internal fun HomeSectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
}

@Composable
internal fun HomeEmptyLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 16.dp),
    )
}

/**
 * Home's chatbox. Deliberately *not* a second copy of `ComposerBar`: it owns a
 * single draft string and hands it to the shell, which routes it into the real
 * composer/send pipeline (select conversation → prefill → navigate → send).
 * Attachments, mentions, slash commands and the model picker stay the chat
 * surface's job — this is the "just say the thing" entry point.
 */
@Composable
private fun HomeComposer(placeholder: String, onSubmit: (String) -> Unit) {
    var draft by remember { mutableStateOf("") }
    val canSend = draft.isNotBlank()
    fun submit() {
        if (!canSend) return
        onSubmit(draft.trim())
        draft = ""
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HomeComposerField(
                text = draft,
                placeholder = placeholder,
                onTextChanged = { draft = it },
                onSubmit = ::submit,
                modifier = Modifier.weight(1f),
            )
            HomeComposerSendButton(canSend = canSend, onSend = ::submit)
        }
    }
}

@Composable
private fun HomeComposerField(
    text: String,
    placeholder: String,
    onTextChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val textStyle = MaterialTheme.typography.bodyLarge.copy(
        color = MaterialTheme.colorScheme.onSurface,
    )
    BasicTextField(
        value = text,
        onValueChange = onTextChanged,
        modifier = modifier
            .heightIn(min = 28.dp, max = 120.dp)
            .padding(vertical = 4.dp)
            .onPreviewKeyEvent { event ->
                val isSend = event.type == KeyEventType.KeyDown &&
                    (event.key == Key.Enter || event.key == Key.NumPadEnter) &&
                    !event.isShiftPressed
                if (isSend) onSubmit()
                isSend
            },
        textStyle = textStyle,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        maxLines = 5,
        decorationBox = { innerTextField ->
            Box(modifier = Modifier.fillMaxWidth()) {
                if (text.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = textStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                innerTextField()
            }
        },
    )
}

@Composable
private fun HomeComposerSendButton(canSend: Boolean, onSend: () -> Unit) {
    Surface(
        onClick = onSend,
        enabled = canSend,
        modifier = Modifier.size(34.dp),
        shape = CircleShape,
        color = if (canSend) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        contentColor = if (canSend) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Outlined.ArrowUpward,
                contentDescription = "Send message",
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

@Composable
private fun RecentConversationRow(
    conversation: FleetRecentConversation,
    orbIndex: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        AgentOrb(index = orbIndex, size = 26.dp, cornerRadius = 7.dp)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = conversation.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (conversation.preview.isNotBlank()) {
                Text(
                    text = conversation.preview,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = conversation.agentName,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(120.dp),
        )
        Text(
            text = formatRelativeTimestamp(conversation.updatedAtLabel),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.width(48.dp),
        )
    }
}
