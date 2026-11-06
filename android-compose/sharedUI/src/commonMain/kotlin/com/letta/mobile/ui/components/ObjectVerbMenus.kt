package com.letta.mobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.letta.mobile.data.objectverbs.ObjectRef
import com.letta.mobile.data.objectverbs.ObjectVerbSnapshot
import com.letta.mobile.data.objectverbs.VerbContext
import com.letta.mobile.data.objectverbs.VerbInvocation
import com.letta.mobile.data.objectverbs.VerbOutcome
import com.letta.mobile.data.objectverbs.binding.PinBinding
import com.letta.mobile.data.objectverbs.registry.CoreVerbProvider
import com.letta.mobile.data.objectverbs.registry.VerbRegistry
import com.letta.mobile.ui.icons.LettaIcons
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A lens supplies identity, presentation context and an optional desired pin action. */
typealias ObjectVerbMenus = (ObjectRef, String, Boolean?) -> List<LettaMenuItem>

/** Render supported descriptors; desired state only selects the matching provider action. */
fun ObjectVerbMenus.items(ref: ObjectRef, lens: String, desiredPinState: Boolean? = null): List<LettaMenuItem> =
    invoke(ref, lens, desiredPinState)

/** One shared, source-bound renderer. The registry owns state, labels, availability and mutations. */
fun objectVerbMenus(
    pins: PinBinding,
    onOutcome: (VerbOutcome, (() -> Unit)?) -> Unit = { _, _ -> },
): ObjectVerbMenus {
    val registry = pins.registry
    return { ref, lens, desired ->
        val snapshot = registry.describe(ref, VerbContext(lens))
            ?.forRendering(CoreVerbProvider.KINDS, setOf(CoreVerbProvider.PIN))
        snapshot?.menuItems(registry, desired, onOutcome).orEmpty()
    }
}

private fun ObjectVerbSnapshot.menuItems(
    registry: VerbRegistry,
    desired: Boolean?,
    onOutcome: (VerbOutcome, (() -> Unit)?) -> Unit,
): List<LettaMenuItem> = verbs.sortedWith(compareBy({ it.order }, { it.id })).mapNotNull { verb ->
    val pinned = verb.toggle?.pinned ?: return@mapNotNull null
    if (desired == pinned) return@mapNotNull null
    val enabled = verb.availability.state == "enabled"
    val request = VerbInvocation(objectRef, verb.id, context, buildJsonObject { put("pinned", !pinned) })
    LettaMenuItem(
        label = verb.label + (verb.availability.reason?.let { " · $it" } ?: ""),
        icon = when (verb.icon) { "pin" -> LettaIcons.Pin; "pin-off" -> LettaIcons.PinOff; else -> null },
        enabled = enabled,
        onClick = { if (enabled) invokeObjectVerb(registry, request, onOutcome) },
    )
}

private fun invokeObjectVerb(registry: VerbRegistry, request: VerbInvocation, onOutcome: (VerbOutcome, (() -> Unit)?) -> Unit) {
    val outcome = registry.invoke(request)
    val retry = if (outcome is VerbOutcome.Failed && outcome.retryable) {
        { invokeObjectVerb(registry, request, onOutcome) }
    } else null
    onOutcome(outcome, retry)
}

/** Every lens in the workspace reads the same trusted binding. Absent owners expose no actions. */
val LocalObjectVerbs = staticCompositionLocalOf<ObjectVerbMenus?> { null }

/** Refresh descriptors from confirmed state, and withdraw callbacks when the source changes. */
@Composable
fun ProvideObjectVerbs(
    pins: PinBinding?,
    onOutcome: (VerbOutcome, (() -> Unit)?) -> Unit = { _, _ -> },
    content: @Composable () -> Unit,
) {
    val refs = pins?.references?.collectAsState()?.value
    val source = pins?.source?.collectAsState()?.value
    val menus = remember(pins, refs, source, onOutcome) { pins?.let { objectVerbMenus(it, onOutcome) } }
    CompositionLocalProvider(LocalObjectVerbs provides menus, content = content)
}
