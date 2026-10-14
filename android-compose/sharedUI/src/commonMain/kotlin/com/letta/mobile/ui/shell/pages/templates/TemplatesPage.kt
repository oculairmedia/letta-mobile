package com.letta.mobile.ui.shell.pages.templates

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.letta.mobile.data.templates.AgentTemplatesController
import com.letta.mobile.data.templates.AgentTemplatesState
import com.letta.mobile.data.templates.StarterAgentTemplate
import com.letta.mobile.ui.components.LettaEmptyHint
import com.letta.mobile.ui.components.LettaListRowSpec
import com.letta.mobile.ui.components.list.RosterList
import com.letta.mobile.ui.components.list.RosterListRow
import com.letta.mobile.ui.components.list.RosterListTag
import com.letta.mobile.ui.components.list.RosterOptions
import com.letta.mobile.ui.components.list.RosterRowSlots
import com.letta.mobile.ui.components.list.RosterSection
import com.letta.mobile.ui.components.list.RosterSpec
import com.letta.mobile.ui.theme.LettaDimens

/** Test tags of the Templates page. */
object TemplatesPageTags {
    const val PAGE = "templates-page"
    const val ERROR = "templates-page-error"
    const val UNAVAILABLE = "templates-page-unavailable"
    val LIST = RosterListTag("templates-page-list")

    fun create(templateId: String) = "templates-page-create-$templateId"
}

/** What the page reports back: make an agent from a template, dismiss a failure. */
@Immutable
data class TemplatesPageActions(
    val onCreate: (templateId: String) -> Unit = {},
    val onDismissError: () -> Unit = {},
)

/** The page's actions over the shared [AgentTemplatesController]; [onCreated] opens the new agent. */
fun AgentTemplatesController.templatesPageActions(onCreated: (agentId: String) -> Unit) = TemplatesPageActions(
    onCreate = { templateId -> create(templateId, onCreated) },
    onDismissError = ::clearError,
)

/**
 * letta-mobile-ckmub.2.5 (hard cut S17): the starter templates shared by every host, one [RosterList]
 * row each with its Create button. Creating goes through the App Server's `agent_create`; the host
 * opens the new agent. [modifier] carries the host frame's insets.
 */
@Composable
fun TemplatesPage(state: AgentTemplatesState, actions: TemplatesPageActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().testTag(TemplatesPageTags.PAGE).padding(horizontal = LettaDimens.Space.md)) {
        Text(
            INTRO,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = LettaDimens.Space.sm),
        )
        state.createUnavailableReason?.let { reason ->
            Text(
                reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(TemplatesPageTags.UNAVAILABLE),
            )
        }
        state.error?.let { TemplatesError(it, actions.onDismissError) }
        RosterList(
            RosterSpec(
                sections = listOf(RosterSection(key = "templates", items = state.templates)),
                key = StarterAgentTemplate::id,
                options = RosterOptions(testTag = TemplatesPageTags.LIST),
                empty = { LettaEmptyHint(EMPTY, Modifier.padding(LettaDimens.Space.md)) },
            ),
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) { template, row ->
            RosterListRow(
                LettaListRowSpec(title = "${template.icon}  ${template.name}", subtitle = template.description, trailing = template.tags.joinToString(" · ")),
                row,
                RosterRowSlots(end = { CreateButton(template, state, actions) }),
            )
        }
    }
}

@Composable
private fun CreateButton(template: StarterAgentTemplate, state: AgentTemplatesState, actions: TemplatesPageActions) {
    val creating = state.creatingId == template.id
    TextButton(
        onClick = { actions.onCreate(template.id) },
        enabled = state.creatingId == null && state.createUnavailableReason == null,
        modifier = Modifier.testTag(TemplatesPageTags.create(template.id)),
    ) { Text(if (creating) CREATING else CREATE) }
}

@Composable
private fun TemplatesError(message: String, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag(TemplatesPageTags.ERROR), verticalAlignment = Alignment.CenterVertically) {
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss) { Text(DISMISS) }
    }
}

private const val INTRO = "Start a new agent from a template. It opens in a chat once it is created."
private const val EMPTY = "No templates"
private const val CREATE = "Create"
private const val CREATING = "Creating…"
private const val DISMISS = "Dismiss"
