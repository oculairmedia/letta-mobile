package com.letta.mobile.feature.chat

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
import com.letta.mobile.ui.theme.LocalChatFontScale
import com.letta.mobile.ui.theme.scaledBy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

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

@Singleton
class GeneratedUiRegistry @Inject constructor() {
    init {
        // letta-mobile-rnyg: do not fail on reassignment — tests construct
        // multiple instances. Last writer wins.
        INSTANCE = this
    }

    private val renderers: Map<String, GeneratedUiComponentRenderer> = listOf(
        SummaryCardRenderer,
        MetricCardRenderer,
        SuggestionChipsRenderer,
    ).associateBy { it.componentName }

    fun resolve(componentName: String): GeneratedUiComponentRenderer? = renderers[componentName]

    companion object {
        @Volatile
        private var INSTANCE: GeneratedUiRegistry? = null

        /**
         * Static bridge for existing callers. Delegates to the Hilt-managed
         * singleton. `LettaApplication` eagerly injects the instance so the
         * production path goes through the Hilt-built singleton; tests and
         * other callers that reach this before Hilt has built the registry
         * get a lazily-created default instance.
         */
        fun resolve(componentName: String): GeneratedUiComponentRenderer? {
            val instance = INSTANCE ?: synchronized(this) {
                INSTANCE ?: GeneratedUiRegistry().also { INSTANCE = it }
            }
            return instance.resolve(componentName)
        }
    }
}

object SummaryCardRenderer : GeneratedUiComponentRenderer {
    override val componentName: String = "summary_card"

    @Composable
    override fun Render(
        component: UiGeneratedComponent,
        modifier: Modifier,
        onGeneratedUiMessage: ((String) -> Unit)?,
    ) {
        val fontScale = LocalChatFontScale.current
        val model = runCatching {
            generatedUiJson.decodeFromString<SummaryCardProps>(component.propsJson)
        }.getOrNull()

        GeneratedUiCard(
            title = model?.title ?: "Summary",
            modifier = modifier,
        ) {
            model?.body?.takeIf { it.isNotBlank() }?.let {
                Text(text = it, style = MaterialTheme.typography.bodyMedium.scaledBy(fontScale))
            }
            model?.items?.takeIf { it.isNotEmpty() }?.forEach { item ->
                Text(
                    text = "• $item",
                    style = MaterialTheme.typography.bodySmall.scaledBy(fontScale),
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
        val fontScale = LocalChatFontScale.current
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
                    style = MaterialTheme.typography.headlineSmall.scaledBy(fontScale),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            model?.supportingText?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall.scaledBy(fontScale),
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
        val fontScale = LocalChatFontScale.current
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
                    style = MaterialTheme.typography.bodyMedium.scaledBy(fontScale),
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
    val fontScale = LocalChatFontScale.current
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
                style = MaterialTheme.typography.titleSmall.scaledBy(fontScale),
                color = MaterialTheme.colorScheme.onSurface,
            )
            content()
        }
    }
}

@Composable
private fun GeneratedUiFallback(component: UiGeneratedComponent) {
    val fontScale = LocalChatFontScale.current
    component.fallbackText?.takeIf { it.isNotBlank() }?.let {
        Text(text = it, style = MaterialTheme.typography.bodyMedium.scaledBy(fontScale))
    }
    Text(
        text = component.propsJson,
        style = MaterialTheme.typography.bodySmall.scaledBy(fontScale),
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
