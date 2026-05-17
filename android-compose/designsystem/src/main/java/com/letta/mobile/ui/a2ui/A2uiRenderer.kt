package com.letta.mobile.ui.a2ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.a2ui.A2uiBindingResolver
import com.letta.mobile.data.a2ui.A2uiComponent
import com.letta.mobile.data.a2ui.A2uiResolvedBinding
import com.letta.mobile.data.a2ui.A2uiSurfaceManager
import com.letta.mobile.data.a2ui.A2uiSurfaceState
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Immutable
data class A2uiAction(
    val name: String,
    val context: JsonObject? = null,
    val raw: JsonObject,
)

@Composable
fun A2uiRenderer(
    surfaceId: String,
    surfaceManager: A2uiSurfaceManager,
    modifier: Modifier = Modifier,
    onAction: (A2uiAction) -> Unit = {},
) {
    val surfaces by surfaceManager.surfaces.collectAsState()
    A2uiSurfaceRenderer(
        surface = surfaces[surfaceId],
        modifier = modifier,
        onAction = onAction,
    )
}

@Composable
fun A2uiSurfaceRenderer(
    surface: A2uiSurfaceState?,
    modifier: Modifier = Modifier,
    onAction: (A2uiAction) -> Unit = {},
) {
    if (surface == null) {
        A2uiSkeletonCard(modifier = modifier.testTag(A2uiTestTags.SurfaceMissing))
        return
    }

    val root = surface.rootComponentId
        ?.let(surface.components::get)
        ?: surface.components.values.firstOrNull()

    if (root == null) {
        A2uiSkeletonCard(modifier = modifier.testTag(A2uiTestTags.SurfacePending))
        return
    }

    A2uiComponentNode(
        component = root,
        surface = surface,
        modifier = modifier,
        visited = emptySet(),
        onAction = onAction,
    )
}

object A2uiTestTags {
    const val SurfaceMissing = "a2ui_surface_missing"
    const val SurfacePending = "a2ui_surface_pending"
    const val MissingComponent = "a2ui_missing_component"
    const val MissingText = "a2ui_missing_text"
}

@Composable
private fun A2uiComponentNode(
    component: A2uiComponent,
    surface: A2uiSurfaceState,
    modifier: Modifier = Modifier,
    visited: Set<String>,
    onAction: (A2uiAction) -> Unit,
) {
    if (component.id in visited || visited.size > MaxRenderDepth) {
        A2uiSkeletonLine(modifier = modifier.testTag(A2uiTestTags.MissingComponent))
        return
    }
    val nextVisited = visited + component.id
    when (component.component) {
        "Text" -> A2uiText(component = component, surface = surface, modifier = modifier)
        "Column" -> A2uiColumn(
            component = component,
            surface = surface,
            modifier = modifier,
            visited = nextVisited,
            onAction = onAction,
        )
        "Card" -> A2uiCard(
            component = component,
            surface = surface,
            modifier = modifier,
            visited = nextVisited,
            onAction = onAction,
        )
        "Button" -> A2uiButton(
            component = component,
            surface = surface,
            modifier = modifier,
            onAction = onAction,
        )
        else -> A2uiSkeletonLine(modifier = modifier.testTag(A2uiTestTags.MissingComponent))
    }
}

@Composable
private fun A2uiText(
    component: A2uiComponent,
    surface: A2uiSurfaceState,
    modifier: Modifier = Modifier,
) {
    val text = component.resolveText(surface)
    if (text == null) {
        A2uiSkeletonLine(modifier = modifier.testTag(A2uiTestTags.MissingText))
        return
    }
    Text(
        text = text,
        modifier = modifier,
        style = component.textStyle(),
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun A2uiColumn(
    component: A2uiComponent,
    surface: A2uiSurfaceState,
    modifier: Modifier = Modifier,
    visited: Set<String>,
    onAction: (A2uiAction) -> Unit,
) {
    val children = component.children
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(component.spacing()),
    ) {
        if (children.isEmpty()) {
            A2uiSkeletonLine(modifier = Modifier.testTag(A2uiTestTags.MissingComponent))
        }
        children.forEach { childId ->
            val child = surface.components[childId]
            if (child == null) {
                A2uiSkeletonLine(modifier = Modifier.testTag(A2uiTestTags.MissingComponent))
            } else {
                A2uiComponentNode(
                    component = child,
                    surface = surface,
                    visited = visited,
                    onAction = onAction,
                )
            }
        }
    }
}

@Composable
private fun A2uiCard(
    component: A2uiComponent,
    surface: A2uiSurfaceState,
    modifier: Modifier = Modifier,
    visited: Set<String>,
    onAction: (A2uiAction) -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(component.cornerRadius()),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = component.elevation()),
    ) {
        val child = component.child ?: component.children.firstOrNull()
        Box(modifier = Modifier.padding(16.dp)) {
            if (child == null) {
                A2uiSkeletonLine(modifier = Modifier.testTag(A2uiTestTags.MissingComponent))
            } else {
                val childComponent = surface.components[child]
                if (childComponent == null) {
                    A2uiSkeletonLine(modifier = Modifier.testTag(A2uiTestTags.MissingComponent))
                } else {
                    A2uiComponentNode(
                        component = childComponent,
                        surface = surface,
                        visited = visited,
                        onAction = onAction,
                    )
                }
            }
        }
    }
}

@Composable
private fun A2uiButton(
    component: A2uiComponent,
    surface: A2uiSurfaceState,
    modifier: Modifier = Modifier,
    onAction: (A2uiAction) -> Unit,
) {
    val label = component.resolveButtonLabel(surface)
    val action = component.action()
    Button(
        onClick = { action?.let(onAction) },
        enabled = label != null && action != null,
        modifier = modifier,
    ) {
        if (label == null) {
            A2uiSkeletonLine(
                modifier = Modifier
                    .widthIn(min = 72.dp)
                    .testTag(A2uiTestTags.MissingText),
                height = 12.dp,
            )
        } else {
            Text(text = label)
        }
    }
}

@Composable
private fun A2uiSkeletonCard(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            A2uiSkeletonLine(widthFraction = 0.65f)
            A2uiSkeletonLine(widthFraction = 1f)
            A2uiSkeletonLine(widthFraction = 0.8f)
        }
    }
}

@Composable
private fun A2uiSkeletonLine(
    modifier: Modifier = Modifier,
    widthFraction: Float = 0.7f,
    height: Dp = 14.dp,
) {
    Box(
        modifier = modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f))
    )
}

@Composable
private fun A2uiComponent.textStyle(): TextStyle = when (raw.stringValue("variant")) {
    "h1" -> MaterialTheme.typography.displaySmall
    "h2" -> MaterialTheme.typography.headlineLarge
    "h3" -> MaterialTheme.typography.headlineMedium
    "h4" -> MaterialTheme.typography.headlineSmall
    "h5" -> MaterialTheme.typography.titleLarge
    "h6" -> MaterialTheme.typography.titleMedium
    "label" -> MaterialTheme.typography.labelLarge
    "caption" -> MaterialTheme.typography.bodySmall
    else -> MaterialTheme.typography.bodyMedium
}

@Composable
private fun A2uiComponent.resolveText(surface: A2uiSurfaceState): String? {
    val binding = raw["text"] ?: raw["content"] ?: raw["value"]
    return resolveBindingText(binding, surface)
}

@Composable
private fun A2uiComponent.resolveButtonLabel(surface: A2uiSurfaceState): String? {
    raw.stringValue("labelComponentId", "labelId")?.let { labelId ->
        return surface.components[labelId]?.resolveText(surface)
    }
    val label = raw["label"]
    if (label is JsonPrimitive) {
        surface.components[label.contentOrNull]?.resolveText(surface)?.let { return it }
    }
    return resolveBindingText(label ?: raw["text"], surface)
}

@Composable
private fun resolveBindingText(binding: JsonElement?, surface: A2uiSurfaceState): String? =
    when {
        binding is JsonObject && binding.stringValue("path") != null -> {
            val value by surface.dataModel.observe(binding.stringValue("path").orEmpty())
            value?.let(A2uiBindingResolver::displayText)
        }
        else -> when (val resolved = A2uiBindingResolver.resolve(binding, surface.dataModel)) {
            A2uiResolvedBinding.Missing -> null
            is A2uiResolvedBinding.Value -> A2uiBindingResolver.displayText(resolved.value)
        }
    }

private fun A2uiComponent.action(): A2uiAction? {
    val action = (raw["action"] ?: raw["onClick"]) as? JsonObject ?: return null
    val name = action.stringValue("name", "type", "action") ?: return null
    val context = (action["context"] ?: action["data"]) as? JsonObject
    return A2uiAction(name = name, context = context, raw = action)
}

private fun A2uiComponent.spacing(): Dp =
    raw.dpValue("spacing") ?: when (raw.stringValue("spacing")) {
        "none" -> 0.dp
        "xs" -> 4.dp
        "sm" -> 8.dp
        "md" -> 12.dp
        "lg" -> 16.dp
        "xl" -> 24.dp
        else -> 8.dp
    }

private fun A2uiComponent.cornerRadius(): Dp =
    raw.dpValue("cornerRadius", "corner_radius") ?: 12.dp

private fun A2uiComponent.elevation(): Dp =
    raw.dpValue("elevation") ?: 1.dp

private fun JsonObject.stringValue(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { key -> this[key]?.jsonPrimitiveOrNull?.contentOrNull }

private fun JsonObject.dpValue(vararg keys: String): Dp? =
    stringValue(*keys)?.toFloatOrNull()?.coerceIn(0f, 64f)?.dp

private val JsonElement.jsonPrimitiveOrNull: JsonPrimitive?
    get() = this as? JsonPrimitive

private const val MaxRenderDepth = 32
