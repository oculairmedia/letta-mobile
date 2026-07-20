package com.letta.mobile.ui.screens.conversations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.letta.mobile.R
import com.letta.mobile.ui.screens.agentlist.LocalLettaCodeCreateReadiness
import com.letta.mobile.ui.icons.LettaIcons

@Composable
internal fun FirstRunWelcomeCard(
    localReadiness: LocalLettaCodeCreateReadiness,
    onCreateFirstAgent: () -> Unit,
    onOpenLocalSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(
                    imageVector = LettaIcons.Agent,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp),
                )
                Text(
                    text = stringResource(R.string.screen_conversations_first_run_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.screen_conversations_first_run_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FirstRunStep(text = stringResource(R.string.screen_conversations_first_run_step_create))
                FirstRunStep(text = stringResource(R.string.screen_conversations_first_run_step_runtime))
                FirstRunStep(text = stringResource(R.string.screen_conversations_first_run_step_chat))
                Button(
                    onClick = onCreateFirstAgent,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.screen_conversations_first_run_create_agent))
                }
                if (localReadiness.activeConfigIsLocal && !localReadiness.ready) {
                    Text(
                        text = localReadiness.setupMessage.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = onOpenLocalSettings,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(localReadiness.setupActionLabel)
                    }
                }
            }
        }
    }
}

@Composable
private fun FirstRunStep(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = LettaIcons.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
