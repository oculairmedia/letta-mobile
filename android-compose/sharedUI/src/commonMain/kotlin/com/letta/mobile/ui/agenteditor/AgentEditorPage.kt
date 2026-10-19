package com.letta.mobile.ui.agenteditor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.data.agenteditor.AgentCreated
import com.letta.mobile.data.agenteditor.AgentEditorActions
import com.letta.mobile.data.agenteditor.AgentEditorCwdDefaults
import com.letta.mobile.data.agenteditor.AgentEditorLinks
import com.letta.mobile.data.agenteditor.AgentEditorOpeners
import com.letta.mobile.data.agenteditor.AgentEditorSections
import com.letta.mobile.data.agenteditor.AgentEditorState
import com.letta.mobile.data.agenteditor.errorNote
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.ui.runtime.LocalAgentDefaults
import com.letta.mobile.data.settings.SettingsAction
import com.letta.mobile.data.settings.SettingsRow
import com.letta.mobile.data.settings.SettingsSectionModel
import com.letta.mobile.data.settings.SettingsStatus
import com.letta.mobile.data.settings.SettingsTone
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.ui.mascot.MascotAvatar
import com.letta.mobile.ui.mascot.MascotLoading
import com.letta.mobile.ui.mascot.MascotShapeGlyph
import com.letta.mobile.ui.settings.SettingsLayout
import com.letta.mobile.ui.settings.SettingsModelSection
import com.letta.mobile.ui.shell.header.HeaderAction
import com.letta.mobile.ui.shell.header.HeaderControls
import com.letta.mobile.ui.shell.header.HeaderNav
import com.letta.mobile.ui.shell.header.HeaderTitle
import com.letta.mobile.ui.shell.header.ShellHeader
import com.letta.mobile.ui.shell.scaffold.LettaPageScaffold
import com.letta.mobile.ui.theme.LettaDimens

/** Test handles of the agent editor page. */
object AgentEditorPageTags {
    const val PAGE = "agent-editor-page"
    const val SAVE = "agent-editor-save"
    const val LOADING = "agent-editor-loading"
    const val HERO = "agent-editor-hero"
    const val MASCOT_PANE = "agent-editor-mascot-pane"
    const val MODEL_PANE = "agent-editor-model-pane"
    const val TOOLS_PANE = "agent-editor-tools-pane"
    const val PANEL_PANE = "agent-editor-panel-pane"
}

/**
 * The platform-bound panels the editor opens in a pane: the shared Dreaming panel and the agent's
 * secrets vault, each bound by its host to that host's services. Null leaves the row out.
 */
class AgentEditorPanels(
    val dreaming: (@Composable () -> Unit)? = null,
    val secrets: (@Composable () -> Unit)? = null,
)

/**
 * How a host wires the page: where Back, a save and a delete go, the agent's other pages, its
 * panels, the device store the mascot is cached in, and who else hears a mascot pick (the desktop's
 * orbs). [embedded] drops the page's own header where the host frames it (the desktop side pane).
 */
class AgentEditorPageOptions(
    val onBack: (() -> Unit)? = null,
    val onSaved: (AgentCreated) -> Unit = {},
    val onDeleted: () -> Unit = {},
    val links: AgentEditorLinks = AgentEditorLinks(),
    val panels: AgentEditorPanels = AgentEditorPanels(),
    val settings: SecureSettingsStore = NoSettingsStore,
    val onIdentityChange: (agentId: String, identity: MascotIdentity) -> Unit = { _, _ -> },
    val embedded: Boolean = false,
)

/** The panes the page opens. */
internal enum class AgentEditorPane { Mascot, Model, Tools, Dreaming, Secrets }

/**
 *  (hard cut S15): the one agent editor of every client, for an existing agent
 * and for a new one, drawn through ShellPage's AgentEditor destination. Sections are the Settings
 * page's rows (SettingsModelSection over [AgentEditorSections]); the model and tool pickers are the
 * shared LettaPicker, the mascot the shared MascotPicker, all in LettaPanes. The page owns its insets
 * through [LettaPageScaffold] (edge to edge on Android) and waits as the agent (MascotLoading).
 */
@Composable
fun AgentEditorPage(
    state: AgentEditorState,
    actions: AgentEditorActions,
    options: AgentEditorPageOptions = AgentEditorPageOptions(),
    modifier: Modifier = Modifier,
) {
    var pane by remember { mutableStateOf<AgentEditorPane?>(null) }
    val identity = rememberAgentEditorIdentity(state, actions, options)
    LettaPageScaffold(
        modifier = modifier.testTag(AgentEditorPageTags.PAGE),
        containerColor = MaterialTheme.colorScheme.background,
        header = { if (!options.embedded) AgentEditorHeader(state, actions, options) },
    ) { insets ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(insets)) {
            val layout = SettingsLayout.of(maxWidth)
            when {
                state.loading -> AgentEditorLoading(state.agentId)
                state.loadError != null -> AgentEditorLoadFailed(state.loadError.orEmpty(), actions::retry)
                else -> AgentEditorBody(AgentEditorBodyInputs(state, actions, options, identity, layout)) { pane = it }
            }
        }
    }
    pane?.let { open -> AgentEditorPaneHost(open, AgentEditorPaneInputs(state, actions, options, identity)) { pane = null } }
}

@Composable
private fun AgentEditorHeader(state: AgentEditorState, actions: AgentEditorActions, options: AgentEditorPageOptions) {
    ShellHeader(
        title = HeaderTitle.Text(if (state.creating) "New agent" else "Edit agent", subtitle = state.agent?.name),
        controls = HeaderControls(
            nav = options.onBack?.let { HeaderNav.Back(it) } ?: HeaderNav.None,
            actions = listOf(HeaderAction.Custom { AgentEditorSaveButton(state, actions, options) }),
        ),
    )
}

@Composable
internal fun AgentEditorSaveButton(state: AgentEditorState, actions: AgentEditorActions, options: AgentEditorPageOptions) {
    val label = when {
        state.saving -> "Saving…"
        state.creating -> "Create"
        else -> "Save"
    }
    TextButton(
        onClick = { actions.save(options.onSaved) },
        enabled = state.canSave,
        modifier = Modifier.testTag(AgentEditorPageTags.SAVE),
    ) { Text(label) }
}

@Composable
private fun AgentEditorLoading(agentId: String?) {
    Box(Modifier.fillMaxSize().testTag(AgentEditorPageTags.LOADING), contentAlignment = Alignment.Center) {
        MascotLoading(agentId)
    }
}

@Composable
private fun AgentEditorLoadFailed(message: String, onRetry: () -> Unit) {
    val section = SettingsSectionModel(
        key = "agent-load-failed",
        title = "Could not open the agent",
        rows = listOf(
            SettingsRow.Note("load-error", SettingsStatus(message, SettingsTone.Error)),
            SettingsRow.Actions("load-actions", listOf(SettingsAction("retry", "Try again", primary = true) { onRetry() })),
        ),
    )
    Box(Modifier.fillMaxSize().padding(LettaDimens.Space.lg), contentAlignment = Alignment.TopCenter) {
        SettingsModelSection(section, Modifier.widthIn(max = LettaDimens.Pane.settingsColumnMaxWidth))
    }
}

/** What the scrolling body draws from. */
private class AgentEditorBodyInputs(
    val state: AgentEditorState,
    val actions: AgentEditorActions,
    val options: AgentEditorPageOptions,
    val identity: AgentEditorIdentity,
    val layout: SettingsLayout,
)

@Composable
private fun AgentEditorBody(inputs: AgentEditorBodyInputs, onOpen: (AgentEditorPane) -> Unit) {
    val cwdDefaults = rememberAgentEditorCwd(inputs)
    val sections = AgentEditorSections(
        inputs.state,
        inputs.actions,
        inputs.openers(onOpen),
        cwdDefaults,
    ).build()
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = inputs.layout.columnMaxWidth)
                .fillMaxWidth()
                .padding(horizontal = inputs.layout.gutter, vertical = LettaDimens.Space.lg),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
        ) {
            AgentEditorHero(inputs, onOpen)
            inputs.state.errorNote()?.let { note ->
                SettingsModelSection(SettingsSectionModel("agent-error", "Something went wrong", listOf(note)))
            }
            sections.forEach { SettingsModelSection(it) }
        }
    }
}

private fun AgentEditorBodyInputs.openers(
    onOpen: (AgentEditorPane) -> Unit,
) = AgentEditorOpeners(
    mascotLabel = identity.current.label,
    onMascot = { onOpen(AgentEditorPane.Mascot) },
    onModel = { onOpen(AgentEditorPane.Model) },
    onTools = { onOpen(AgentEditorPane.Tools) },
    onDeleted = options.onDeleted,
    links = options.links.copy(
        dreaming = options.panels.dreaming?.let { { onOpen(AgentEditorPane.Dreaming) } },
        secrets = options.panels.secrets?.let { { onOpen(AgentEditorPane.Secrets) } },
    ),
)

/**
 * An existing agent's saved default cwd, from the host's one
 * [LocalAgentDefaults] (the store its turn engine reads). A host that binds none shows no row.
 */
@Composable
private fun rememberAgentEditorCwd(inputs: AgentEditorBodyInputs): AgentEditorCwdDefaults? {
    val repo = LocalAgentDefaults.current ?: return null
    val entries by repo.entries.collectAsState()
    val agentId = inputs.state.agentId?.takeIf { !inputs.state.creating } ?: return null
    return AgentEditorCwdDefaults(
        path = entries[agentId]?.cwd.orEmpty(),
        onChange = { repo.setCwd(AgentId(agentId), it) },
    )
}

/** The agent as it looks, its name beside it; in a host that frames the page, the Save too. */
@Composable
private fun AgentEditorHero(inputs: AgentEditorBodyInputs, onOpen: (AgentEditorPane) -> Unit) {
    val identity = inputs.identity.current
    Row(
        modifier = Modifier.fillMaxWidth().testTag(AgentEditorPageTags.HERO),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.lg),
    ) {
        MascotAvatar(
            agentId = inputs.state.agentId,
            size = HeroSize,
            cornerRadius = HeroSize / 2,
            onClick = { onOpen(AgentEditorPane.Mascot) },
            fallback = { MascotShapeGlyph(identity.shape, identity.argb, HeroSize) },
        )
        Column(Modifier.weight(1f)) {
            Text(
                inputs.state.form.name.ifBlank { if (inputs.state.creating) "New agent" else "Unnamed agent" },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            inputs.state.modelOption?.label?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (inputs.options.embedded) AgentEditorSaveButton(inputs.state, inputs.actions, inputs.options)
    }
}

private val HeroSize = LettaDimens.Orb.lg
