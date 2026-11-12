package com.letta.mobile.ui.secrets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.letta.mobile.data.secrets.request.SecretRequestRecord
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.secret_request_card_title
import com.letta.mobile.sharedui.resources.secret_request_expires_minutes
import com.letta.mobile.sharedui.resources.secret_request_expires_now
import com.letta.mobile.sharedui.resources.secret_request_more_waiting
import com.letta.mobile.sharedui.resources.secret_request_open
import com.letta.mobile.sharedui.resources.secret_request_open_description
import com.letta.mobile.sharedui.resources.secret_request_purpose_label
import com.letta.mobile.sharedui.resources.secret_request_target
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Test tags of [SecretRequestCard]. */
object SecretRequestCardTags {
    /** The card. */
    const val CARD = "secret-request-card"

    /** The button that opens the secure form. */
    const val OPEN = "secret-request-card-open"
}

/**
 * What one card shows: the [request] (metadata only), who asked as the person knows them
 * ([agentLabel]), the whole [minutesLeft] before it expires (0 for under a minute), and how many
 * other requests are [othersWaiting] behind it.
 */
@Immutable
data class SecretRequestCardModel(
    /** The pending request. */
    val request: SecretRequestRecord,
    /** The requesting agent's name, or its id when the host has no name for it. */
    val agentLabel: String,
    /** Whole minutes until the request expires; 0 for less than a minute. */
    val minutesLeft: Int,
    /** How many other pending requests wait behind this one. */
    val othersWaiting: Int = 0,
)

/**
 * The actionable summary of a pending secret request: who asks, for which vault item and field,
 * the agent's purpose (agent-authored plain text, labelled as such), when it expires, and a button
 * that opens the secure form. It carries no value and needs no canvas or conversation, so any lens
 * can mount it; the shell mounts it above the main lane while a request is pending.
 */
@Composable
fun SecretRequestCard(model: SecretRequestCardModel, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val details = model.request.details
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth().testTag(SecretRequestCardTags.CARD),
    ) {
        Column(Modifier.padding(LettaDimens.Space.lg), verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
            Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm), verticalAlignment = Alignment.CenterVertically) {
                Icon(LettaIcons.Key, contentDescription = null, modifier = Modifier.size(LettaDimens.Control.icon))
                Text(stringResource(Res.string.secret_request_card_title, model.agentLabel), style = MaterialTheme.typography.titleSmall)
            }
            val target = details.target
            Text(
                stringResource(Res.string.secret_request_target, target.item.value, target.field.value, target.keystore.value),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(stringResource(Res.string.secret_request_purpose_label), style = MaterialTheme.typography.labelSmall)
            // Agent-authored plain text: never markdown, never links.
            Text(details.purpose.text.value, style = MaterialTheme.typography.bodyMedium)
            CardFooter(model, onOpen)
        }
    }
}

@Composable
private fun CardFooter(model: SecretRequestCardModel, onOpen: () -> Unit) {
    val expiry = if (model.minutesLeft > 0) {
        pluralStringResource(Res.plurals.secret_request_expires_minutes, model.minutesLeft, model.minutesLeft)
    } else {
        stringResource(Res.string.secret_request_expires_now)
    }
    Text(expiry, style = MaterialTheme.typography.labelMedium)
    if (model.othersWaiting > 0) {
        Text(
            pluralStringResource(Res.plurals.secret_request_more_waiting, model.othersWaiting, model.othersWaiting),
            style = MaterialTheme.typography.labelMedium,
        )
    }
    val description = stringResource(Res.string.secret_request_open_description, model.agentLabel)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Button(onClick = onOpen, modifier = Modifier.semantics { contentDescription = description }.testTag(SecretRequestCardTags.OPEN)) {
            Text(stringResource(Res.string.secret_request_open))
        }
    }
}
