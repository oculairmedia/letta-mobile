package com.letta.mobile.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.mascot.AgentAvatar
import com.letta.mobile.ui.mascot.mascotAtWork
import com.letta.mobile.ui.theme.LettaDimens

object AgentIdentityPillTestTags {
    /** The pill's surface: one per screen, wherever it is drawn. */
    const val PILL = "agent_identity_pill"

    /** The tappable identity row inside it (tap: switch agents; long press: pin). */
    const val TRIGGER = "agent_identity_pill_trigger"
}

/**
 * The chat header's black pill: the shape, colours, padding and type style every agent pill
 * shares. It sets its own text style rather than inheriting one, so it looks the same in an app
 * bar's title slot (which would hand it titleLarge) and floating on its own (which would not).
 * The header also draws its search field inside it.
 */
@Composable
fun AgentPillSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        color = Color.Black,
        contentColor = Color.White,
    ) {
        ProvideTextStyle(MaterialTheme.typography.titleLarge) {
            Box(Modifier.padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.md)) {
                content()
            }
        }
    }
}

/** What [AgentIdentityPill] shows and does: tap switches agents, long press pins. */
data class AgentIdentity(
    val agentId: String,
    val name: String,
    val isFavorite: Boolean,
    val isPinned: Boolean,
    val onClick: () -> Unit,
    val onLongClick: () -> Unit,
)

/**
 * letta-mobile-bglj6.1.22: the agent at a glance - avatar, name, favourite and pin marks and the
 * switcher chevron in [AgentPillSurface]. The one element for the chat screen's header and the
 * phone canvas mode, which keeps it alone where the header was; callers only place it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AgentIdentityPill(identity: AgentIdentity, modifier: Modifier = Modifier) {
    val agentId = identity.agentId
    val name = identity.name
    AgentPillSurface(modifier.testTag(AgentIdentityPillTestTags.PILL)) {
        Row(
            modifier = Modifier
                .testTag(AgentIdentityPillTestTags.TRIGGER)
                .combinedClickable(onClick = identity.onClick, onLongClick = identity.onLongClick)
                .padding(end = LettaDimens.Space.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
        ) {
            // The agent's avatar as a chip before its name (letta-mobile-8jtf3). Alive while the
            // agent is idle; the moment a run starts it turns into a still and the composer
            // companion carries the motion - one moving character per screen.
            AgentAvatar(
                agentId = agentId,
                name = name,
                size = AvatarSize,
                modifier = Modifier.padding(end = LettaDimens.Space.xs),
                live = !mascotAtWork(agentId),
            )
            Text(
                text = name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (identity.isFavorite) {
                Icon(
                    LettaIcons.Star,
                    contentDescription = "Favorite agent",
                    modifier = Modifier.size(MarkSize),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            if (identity.isPinned) {
                Icon(
                    LettaIcons.Pin,
                    contentDescription = "Pinned agent",
                    modifier = Modifier.size(MarkSize),
                    tint = MaterialTheme.colorScheme.tertiary,
                )
            }
            Icon(
                LettaIcons.ArrowDropDown,
                contentDescription = "Switch agent",
                modifier = Modifier.size(MarkSize),
                tint = LocalContentColor.current,
            )
        }
    }
}

private val AvatarSize = LettaDimens.Space.xxl
private val MarkSize = LettaDimens.Space.lg
