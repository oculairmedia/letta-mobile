package com.letta.mobile.desktop.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.desktop.DesktopControlText
import com.letta.mobile.desktop.DesktopDefaultButton
import com.letta.mobile.desktop.DesktopButtonContent
import com.letta.mobile.desktop.DesktopTextArea
import com.letta.mobile.desktop.DesktopTextField
import com.letta.mobile.desktop.chat.AgentOrb
import com.letta.mobile.desktop.memory.DesktopBlockApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import org.jetbrains.jewel.ui.component.PopupMenu as JewelPopupMenu

private val ToneOptions = listOf("Concise", "Friendly", "Technical", "Mentor", "Playful", "Formal")
private val VoiceOptions = listOf("Caring", "Neutral", "Warm", "Energetic", "Calm", "Direct")
private const val AvatarStyleCount = 6

// Core-memory block labels the editor reads/writes. Persona is the standard
// Letta persona block; the rest are app-defined labelled blocks so the values
// actually sit in the agent's context (and so they round-trip with zero backend
// work — block CRUD already exists). Avatar style and voice are pure display
// config and live in desktop-local settings instead, to avoid burning context.
private const val PersonaLabel = "persona"
private const val ToneLabel = "tone"
private const val InstructionsLabel = "instructions"
private const val InterestsLabel = "interests"

internal fun agentAvatarStyleKey(agentId: String): String = "agent.$agentId.avatar_style"
internal fun agentVoiceKey(agentId: String): String = "agent.$agentId.voice"

/**
 * Full-page agent editor (Penpot "Desktop · Edit Agent"). Rendered in the main
 * content pane while [agentId] is being edited, with the rail + sidebar still
 * visible.
 *
 * Storage is tiered by whether a field belongs in the agent's context:
 *  - Name and Default model -> [IAgentRepository.updateAgent].
 *  - Persona, Tone, Custom instructions, Interests -> labelled core-memory
 *    BLOCKS via [DesktopBlockApi] (created+attached on first save, updated
 *    thereafter), so they persist AND actually steer the agent.
 *  - Avatar style and Voice -> desktop-local [SecureSettingsStore], since
 *    display-only config shouldn't occupy the context window.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DesktopEditAgentSurface(
    agentId: String,
    modelOptions: List<Pair<String, String>>,
    agentRepository: IAgentRepository,
    blockApi: DesktopBlockApi?,
    settings: SecureSettingsStore,
    scope: CoroutineScope,
    onClose: () -> Unit,
    onSaved: (avatarStyle: Int, nameChanged: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember(agentId) { mutableStateOf("") }
    var avatarStyle by remember(agentId) { mutableStateOf(0) }
    var persona by remember(agentId) { mutableStateOf("") }
    var tone by remember(agentId) { mutableStateOf<String?>(null) }
    var customInstructions by remember(agentId) { mutableStateOf("") }
    val interests = remember(agentId) { mutableStateListOf<String>() }
    var voice by remember(agentId) { mutableStateOf(VoiceOptions.first()) }
    var modelValue by remember(agentId) { mutableStateOf<String?>(null) }

    var personaBlockId by remember(agentId) { mutableStateOf<String?>(null) }
    var toneBlockId by remember(agentId) { mutableStateOf<String?>(null) }
    var instructionsBlockId by remember(agentId) { mutableStateOf<String?>(null) }
    var interestsBlockId by remember(agentId) { mutableStateOf<String?>(null) }
    var loading by remember(agentId) { mutableStateOf(true) }
    var busy by remember(agentId) { mutableStateOf(false) }
    var error by remember(agentId) { mutableStateOf<String?>(null) }
    var newInterest by remember(agentId) { mutableStateOf("") }

    // Originals captured at load so save only writes fields that actually changed.
    var loadedName by remember(agentId) { mutableStateOf("") }
    var loadedModel by remember(agentId) { mutableStateOf<String?>(null) }
    var loadedPersona by remember(agentId) { mutableStateOf("") }
    var loadedTone by remember(agentId) { mutableStateOf<String?>(null) }
    var loadedInstructions by remember(agentId) { mutableStateOf("") }
    var loadedInterests by remember(agentId) { mutableStateOf("") }

    LaunchedEffect(agentId) {
        // Fetch fresh — the flow's last emission re-fetches and refreshes the
        // cache — so a persona/block edited last time isn't shown stale on reopen
        // (and its block ids are current, so a re-save updates rather than
        // creating a duplicate). Fall back to any cached copy if the fetch fails.
        val agent = runCatching { agentRepository.getAgent(agentId).last() }.getOrNull()
            ?: agentRepository.getCachedAgent(agentId)
        if (agent == null) {
            error = "Could not load agent"
            loading = false
            return@LaunchedEffect
        }
        name = agent.name
        modelValue = agent.model
        fun block(label: String) = agent.blocks.firstOrNull { it.label == label }
        block(PersonaLabel).let { personaBlockId = it?.id?.value; persona = it?.value.orEmpty() }
        block(ToneLabel).let { toneBlockId = it?.id?.value; tone = it?.value?.takeIf { v -> v in ToneOptions } }
        block(InstructionsLabel).let { instructionsBlockId = it?.id?.value; customInstructions = it?.value.orEmpty() }
        block(InterestsLabel).let { b ->
            interestsBlockId = b?.id?.value
            interests.clear()
            b?.value?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.let { interests.addAll(it) }
        }
        avatarStyle = settings.getString(agentAvatarStyleKey(agentId))?.toIntOrNull()?.coerceIn(0, AvatarStyleCount - 1) ?: 0
        voice = settings.getString(agentVoiceKey(agentId))?.takeIf { it in VoiceOptions } ?: VoiceOptions.first()
        loadedName = name
        loadedModel = modelValue
        loadedPersona = persona
        loadedTone = tone
        loadedInstructions = customInstructions
        loadedInterests = interests.joinToString(", ")
        loading = false
    }

    val modelLabel = modelOptions.firstOrNull { it.second == modelValue }?.first ?: modelValue ?: "Default"
    val accent = MaterialTheme.colorScheme.primary

    fun save() {
        // Commit a pending interest the user typed but didn't enter (no trailing
        // comma), so it isn't silently dropped on save.
        newInterest.trim().takeIf { it.isNotEmpty() && it !in interests }?.let { interests.add(it) }
        newInterest = ""
        busy = true
        error = null
        // Only the name affects the rail/sidebar, so only a name change needs the
        // (heavy) roster reload after save.
        val nameChanged = name.trim() != loadedName.trim()
        scope.launch {
            runCatching {
                // Returns the block's id (existing or freshly-created) so it's
                // captured back into state — a re-save then UPDATEs in place
                // instead of attaching a second block.
                suspend fun upsertBlock(label: String, value: String, existingId: String?): String? = when {
                    existingId != null -> { blockApi?.updateBlockById(existingId, value); existingId }
                    value.isNotBlank() -> blockApi?.createAndAttachBlock(agentId, label, value)?.id?.value
                    else -> existingId
                }
                // Write ONLY the fields that changed, and run those writes
                // concurrently — the agent PATCH alone re-fetches the whole roster
                // and each block is its own round-trip, so doing them in series is
                // the save lag. Unchanged fields cost nothing.
                coroutineScope {
                    buildList<Deferred<Unit>> {
                        if (nameChanged || modelValue != loadedModel) add(
                            async {
                                agentRepository.updateAgent(
                                    AgentId(agentId),
                                    AgentUpdateParams(
                                        name = name.trim().takeIf { it.isNotBlank() },
                                        model = modelValue,
                                    ),
                                )
                                Unit
                            },
                        )
                        if (persona != loadedPersona) add(async { personaBlockId = upsertBlock(PersonaLabel, persona, personaBlockId) })
                        if (tone != loadedTone) add(async { toneBlockId = upsertBlock(ToneLabel, tone.orEmpty(), toneBlockId) })
                        if (customInstructions.trim() != loadedInstructions.trim()) {
                            add(async { instructionsBlockId = upsertBlock(InstructionsLabel, customInstructions.trim(), instructionsBlockId) })
                        }
                        if (interests.joinToString(", ") != loadedInterests) {
                            add(async { interestsBlockId = upsertBlock(InterestsLabel, interests.joinToString(", "), interestsBlockId) })
                        }
                    }.awaitAll()
                }
                // Display-only config stays local (and out of the context window).
                settings.putString(agentAvatarStyleKey(agentId), avatarStyle.toString())
                settings.putString(agentVoiceKey(agentId), voice)
            }
                .onSuccess { onSaved(avatarStyle, nameChanged) }
                .onFailure { error = it.message ?: "Save failed"; busy = false }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Scrollable content; the Save bar is pinned to the bottom (below).
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 40.dp, top = 28.dp, end = 40.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // Header: back · title
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Edit agent",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            if (loading) {
                Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Column(
                    modifier = Modifier.widthIn(max = 980.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
            // Avatar + Name
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                AgentOrb(index = avatarStyle, size = 64.dp, cornerRadius = 12.dp)
                LabeledSection("Name", accent, Modifier.weight(1f)) {
                    DesktopTextField(value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth())
                }
            }

            // Avatar style swatches
            LabeledSection("Avatar style", accent) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(AvatarStyleCount) { i ->
                        val selected = i == avatarStyle
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(
                                    width = if (selected) 2.dp else 0.dp,
                                    color = if (selected) accent else Color.Transparent,
                                    shape = RoundedCornerShape(8.dp),
                                )
                                .clickable { avatarStyle = i }
                                .padding(if (selected) 3.dp else 0.dp),
                        ) {
                            AgentOrb(index = i, size = if (selected) 28.dp else 34.dp, cornerRadius = 6.dp)
                        }
                    }
                }
            }

            // Persona · backstory → persona memory block
            LabeledSection("Persona · backstory", accent) {
                DesktopTextArea(
                    value = persona,
                    onValueChange = { persona = it },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(110.dp),
                    placeholder = "Describe who this agent is…",
                )
                Text(
                    "Writes to the agent's persona memory block",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }

            // Tone
            LabeledSection("Tone", accent) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToneOptions.forEach { option ->
                        SelectChip(text = option, selected = tone == option) {
                            tone = if (tone == option) null else option
                        }
                    }
                }
            }

            // Custom instructions
            LabeledSection("Custom instructions", accent) {
                DesktopTextArea(
                    value = customInstructions,
                    onValueChange = { customInstructions = it },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(90.dp),
                    placeholder = "Anything else the agent should always keep in mind…",
                )
            }

            // Interests
            LabeledSection("Interests", accent) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    interests.forEach { interest ->
                        InterestChip(text = interest) { interests.remove(interest) }
                    }
                    Box(modifier = Modifier.width(150.dp)) {
                        DesktopTextField(
                            value = newInterest,
                            onValueChange = { value ->
                                if (value.endsWith(",") || value.endsWith("\n")) {
                                    val tag = value.trimEnd(',', '\n').trim()
                                    if (tag.isNotEmpty() && tag !in interests) interests.add(tag)
                                    newInterest = ""
                                } else {
                                    newInterest = value
                                }
                            },
                            placeholder = "+ Add",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            // Voice + Default model (side by side)
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                LabeledSection("Voice", accent, Modifier.weight(1f)) {
                    DropdownSelector(
                        leading = {
                            Icon(Icons.Outlined.PlayArrow, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                        label = voice,
                        options = VoiceOptions,
                        selected = voice,
                        onSelect = { voice = it },
                    )
                }
                LabeledSection("Default model", accent, Modifier.weight(1f)) {
                    DropdownSelector(
                        leading = null,
                        label = modelLabel,
                        options = modelOptions.map { it.first },
                        selected = modelLabel,
                        onSelect = { picked -> modelOptions.firstOrNull { it.first == picked }?.let { modelValue = it.second } },
                    )
                }
            }

                    error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        // Pinned Save bar at the bottom — actions stay put without scrolling, and
        // out from under the rail's tooltips up top.
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DesktopDefaultButton(onClick = ::save, enabled = !busy && !loading) {
                DesktopButtonContent(if (busy) "Saving…" else "Save changes")
            }
        }
    }
}

/**
 * One labelled form section: the accent caption + its field(s) with a single,
 * consistent label→content gap. Every section on this screen goes through here
 * so the spacing can't drift field-to-field.
 */
@Composable
private fun LabeledSection(
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(label, accent)
        content()
    }
}

@Composable
private fun SectionLabel(text: String, accent: Color) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = accent,
    )
}

@Composable
private fun SelectChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun InterestChip(text: String, onRemove: () -> Unit) {
    Surface(
        onClick = onRemove,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge)
            Icon(
                Icons.Outlined.Add,
                contentDescription = "Remove $text",
                modifier = Modifier.size(13.dp).rotate(45f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DropdownSelector(
    leading: (@Composable () -> Unit)?,
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                leading?.invoke()
                Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Icon(Icons.Outlined.KeyboardArrowDown, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (open) {
            JewelPopupMenu(onDismissRequest = { open = false; true }, horizontalAlignment = Alignment.Start) {
                options.forEach { option ->
                    selectableItem(selected = option == selected, onClick = { open = false; onSelect(option) }) {
                        DesktopControlText(option)
                    }
                }
            }
        }
    }
}
