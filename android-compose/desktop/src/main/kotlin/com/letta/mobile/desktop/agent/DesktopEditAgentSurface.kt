package com.letta.mobile.desktop.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.letta.mobile.desktop.DesktopControlText
import com.letta.mobile.desktop.DesktopDefaultButton
import com.letta.mobile.desktop.DesktopButtonContent
import com.letta.mobile.desktop.DesktopTextArea
import com.letta.mobile.desktop.DesktopTextField
import com.letta.mobile.desktop.chat.AgentOrb
import com.letta.mobile.desktop.memory.DesktopBlockApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jetbrains.jewel.ui.component.PopupMenu as JewelPopupMenu

private val ToneOptions = listOf("Concise", "Friendly", "Technical", "Mentor", "Playful", "Formal")
private val VoiceOptions = listOf("Caring", "Neutral", "Warm", "Energetic", "Calm", "Direct")
private const val AvatarStyleCount = 6

/**
 * Full-page agent editor (Penpot "Desktop · Edit Agent"). Rendered in the main
 * content pane while [agentId] is being edited, with the rail + sidebar still
 * visible. The behavioural fields map to real agent data — Name and Default
 * model via [IAgentRepository.updateAgent], Persona · Backstory to the agent's
 * `persona` memory block — while the softer presentation fields (avatar style,
 * tone, custom instructions, interests, voice) round-trip through the agent's
 * `metadata`, leaving the raw system prompt and functional tags untouched.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DesktopEditAgentSurface(
    agentId: String,
    modelOptions: List<Pair<String, String>>,
    agentRepository: IAgentRepository,
    blockApi: DesktopBlockApi?,
    scope: CoroutineScope,
    onClose: () -> Unit,
    onSaved: (avatarStyle: Int) -> Unit,
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
    var existingMetadata by remember(agentId) { mutableStateOf<Map<String, JsonElement>>(emptyMap()) }
    var loading by remember(agentId) { mutableStateOf(true) }
    var busy by remember(agentId) { mutableStateOf(false) }
    var error by remember(agentId) { mutableStateOf<String?>(null) }
    var newInterest by remember(agentId) { mutableStateOf("") }

    LaunchedEffect(agentId) {
        val agent = agentRepository.getCachedAgent(agentId)
            ?: runCatching { agentRepository.getAgent(agentId).first() }.getOrNull()
        if (agent == null) {
            error = "Could not load agent"
            loading = false
            return@LaunchedEffect
        }
        name = agent.name
        modelValue = agent.model
        existingMetadata = agent.metadata
        avatarStyle = agent.metadata["avatar_style"].asInt() ?: 0
        tone = agent.metadata["tone"].asString()?.takeIf { it in ToneOptions }
        customInstructions = agent.metadata["custom_instructions"].asString().orEmpty()
        voice = agent.metadata["voice"].asString()?.takeIf { it in VoiceOptions } ?: VoiceOptions.first()
        interests.clear()
        agent.metadata["interests"].asStringList()?.let { interests.addAll(it) }
        val personaBlock = agent.blocks.firstOrNull { it.label == "persona" }
        personaBlockId = personaBlock?.id?.value
        persona = personaBlock?.value.orEmpty()
        loading = false
    }

    val modelLabel = modelOptions.firstOrNull { it.second == modelValue }?.first ?: modelValue ?: "Default"
    val accent = MaterialTheme.colorScheme.primary

    fun save() {
        busy = true
        error = null
        scope.launch {
            runCatching {
                val mergedMeta = existingMetadata.toMutableMap().apply {
                    put("avatar_style", JsonPrimitive(avatarStyle))
                    put("voice", JsonPrimitive(voice))
                    put("interests", JsonArray(interests.map { JsonPrimitive(it) }))
                    if (tone != null) put("tone", JsonPrimitive(tone)) else remove("tone")
                    if (customInstructions.isNotBlank()) {
                        put("custom_instructions", JsonPrimitive(customInstructions.trim()))
                    } else {
                        remove("custom_instructions")
                    }
                }
                agentRepository.updateAgent(
                    AgentId(agentId),
                    AgentUpdateParams(
                        name = name.trim().takeIf { it.isNotBlank() },
                        model = modelValue,
                        metadata = mergedMeta,
                    ),
                )
                val existingPersonaId = personaBlockId
                when {
                    existingPersonaId != null -> blockApi?.updateBlockById(existingPersonaId, persona)
                    persona.isNotBlank() -> blockApi?.createAndAttachBlock(agentId, "persona", persona)
                }
            }
                .onSuccess { onSaved(avatarStyle) }
                .onFailure { error = it.message ?: "Save failed"; busy = false }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 40.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // Header: back · title · Save changes
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
            Spacer(Modifier.weight(1f))
            DesktopDefaultButton(onClick = ::save, enabled = !busy && !loading) {
                DesktopButtonContent(if (busy) "Saving…" else "Save changes")
            }
        }

        if (loading) {
            Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }

        Column(
            modifier = Modifier.widthIn(max = 980.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // Avatar + Name
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                AgentOrb(index = avatarStyle, size = 64.dp, cornerRadius = 12.dp)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionLabel("Name", accent)
                    DesktopTextField(value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth())
                }
            }

            // Avatar style swatches
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Avatar style", accent)
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
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionLabel("Persona · backstory", accent)
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Tone", accent)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToneOptions.forEach { option ->
                        SelectChip(text = option, selected = tone == option) {
                            tone = if (tone == option) null else option
                        }
                    }
                }
            }

            // Custom instructions
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionLabel("Custom instructions", accent)
                DesktopTextArea(
                    value = customInstructions,
                    onValueChange = { customInstructions = it },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(90.dp),
                    placeholder = "Anything else the agent should always keep in mind…",
                )
            }

            // Interests
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Interests", accent)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    interests.forEach { interest ->
                        InterestChip(text = interest) { interests.remove(interest) }
                    }
                    Box(modifier = Modifier.width(150.dp)) {
                        DesktopTextField(
                            value = newInterest,
                            onValueChange = { value ->
                                // Commit on a trailing comma/newline; otherwise keep typing.
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
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel("Voice", accent)
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
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel("Default model", accent)
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

private fun JsonElement?.asString(): String? = (this as? JsonPrimitive)?.contentOrNull
private fun JsonElement?.asInt(): Int? = (this as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
private fun JsonElement?.asStringList(): List<String>? =
    (this as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

/**
 * The agent's chosen avatar-style index (set by the editor's "Avatar style"
 * swatches and stored in metadata), or null if it has never been set — in which
 * case callers fall back to the agent's position-derived orb colour.
 */
internal fun com.letta.mobile.data.model.Agent.avatarStyle(): Int? = metadata["avatar_style"].asInt()
