package com.letta.mobile.ui.screens.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.UiGeneratedComponent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val generatedUiJson = Json { ignoreUnknownKeys = true }

interface GeneratedUiComponentRenderer {
    val componentName: String

    @Composable
    fun Render(
        component: UiGeneratedComponent,
        modifier: Modifier = Modifier,
        onGeneratedUiMessage: ((String) -> Unit)? = null,
    )
}

object GeneratedUiRegistry {
    private val renderers: Map<String, GeneratedUiComponentRenderer> = listOf(
        SummaryCardRenderer,
        MetricCardRenderer,
        SuggestionChipsRenderer,
    ).associateBy { it.componentName }

    fun resolve(componentName: String): GeneratedUiComponentRenderer? = renderers[componentName]
}

object SummaryCardRenderer : GeneratedUiComponentRenderer {
    override val componentName: String = "summary_card"

    @Composable
    override fun Render(
        component: UiGeneratedComponent,
        modifier: Modifier,
        onGeneratedUiMessage: ((String) -> Unit)?,
    ) {
        val model = runCatching {
            generatedUiJson.decodeFromString<SummaryCardProps>(component.propsJson)
        }.getOrNull()

        GeneratedUiCard(
            title = model?.title ?: "Summary",
            modifier = modifier,
        ) {
            model?.body?.takeIf { it.isNotBlank() }?.let {
                Text(text = it, style = MaterialTheme.typography.bodyMedium)
            }
            model?.items?.takeIf { it.isNotEmpty() }?.forEach { item ->
                Text(
                    text = "• $item",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (model == null) {
                GeneratedUiFallback(component = component)
            }
        }
    }
}

object MetricCardRenderer : GeneratedUiComponentRenderer {
    override val componentName: String = "metric_card"

    @Composable
    override fun Render(
        component: UiGeneratedComponent,
        modifier: Modifier,
        onGeneratedUiMessage: ((String) -> Unit)?,
    ) {
        val model = runCatching {
            generatedUiJson.decodeFromString<MetricCardProps>(component.propsJson)
        }.getOrNull()

        GeneratedUiCard(
            title = model?.label ?: "Metric",
            modifier = modifier,
        ) {
            model?.value?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            model?.supportingText?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (model == null) {
                GeneratedUiFallback(component = component)
            }
        }
    }
}

object SuggestionChipsRenderer : GeneratedUiComponentRenderer {
    override val componentName: String = "suggestion_chips"

    @Composable
    override fun Render(
        component: UiGeneratedComponent,
        modifier: Modifier,
        onGeneratedUiMessage: ((String) -> Unit)?,
    ) {
        val model = runCatching {
            generatedUiJson.decodeFromString<SuggestionChipsProps>(component.propsJson)
        }.getOrNull()

        GeneratedUiCard(
            title = model?.title ?: "Suggestions",
            modifier = modifier,
        ) {
            model?.body?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            model?.suggestions?.takeIf { it.isNotEmpty() }?.let { suggestions ->
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    suggestions.forEach { suggestion ->
                        SuggestionChip(
                            onClick = {
                                suggestion.message?.takeIf { it.isNotBlank() }?.let { message ->
                                    onGeneratedUiMessage?.invoke(message)
                                }
                            },
                            enabled = !suggestion.message.isNullOrBlank() && onGeneratedUiMessage != null,
                            label = { Text(suggestion.label) },
                        )
                    }
                }
            }
            if (model == null) {
                GeneratedUiFallback(component = component)
            }
        }
    }
}

@Composable
internal fun GeneratedUiCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            content()
        }
    }
}

@Composable
private fun GeneratedUiFallback(component: UiGeneratedComponent) {
    component.fallbackText?.takeIf { it.isNotBlank() }?.let {
        Text(text = it, style = MaterialTheme.typography.bodyMedium)
    }
    Text(
        text = component.propsJson,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Serializable
data class SummaryCardProps(
    val title: String,
    val body: String? = null,
    val items: List<String> = emptyList(),
)

@Serializable
data class MetricCardProps(
    val label: String,
    val value: String,
    @SerialName("supporting_text") val supportingText: String? = null,
)

@Serializable
data class SuggestionChipsProps(
    val title: String? = null,
    val body: String? = null,
    val suggestions: List<SuggestionChipAction> = emptyList(),
)

@Serializable
data class SuggestionChipAction(
    val label: String,
    val message: String? = null,
)
