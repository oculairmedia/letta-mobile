package com.letta.mobile.ui.screens.usage

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.components.ErrorContent
import com.letta.mobile.ui.components.ShimmerCard
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaTopBarDefaults
import com.letta.mobile.ui.theme.customColors
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.columnSeries
import com.patrykandpatrick.vico.core.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.core.common.Fill
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageScreen(
    onNavigateBack: () -> Unit,
    onNavigateToRuns: () -> Unit,
    viewModel: UsageViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = LettaTopBarDefaults.scaffoldContainerColor(),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.screen_usage_title)) },
                colors = LettaTopBarDefaults.largeTopAppBarColors(),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(LettaIcons.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(LettaIcons.Refresh, stringResource(R.string.action_refresh))
                    }
                },
            )
        },
    ) { paddingValues ->
        when (val state = uiState) {
            is UiState.Loading -> {
                Column(modifier = Modifier.padding(paddingValues).padding(16.dp)) {
                    ShimmerCard(modifier = Modifier.fillMaxWidth())
                }
            }
            is UiState.Error -> {
                ErrorContent(
                    message = state.message,
                    onRetry = { viewModel.refresh() },
                    modifier = Modifier.padding(paddingValues),
                )
            }
            is UiState.Success -> {
                UsageContent(
                    state = state.data,
                    onTimeRangeSelected = viewModel::selectTimeRange,
                    onNavigateToRuns = onNavigateToRuns,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UsageContent(
    state: UsageUiState,
    onTimeRangeSelected: (TimeRange) -> Unit,
    onNavigateToRuns: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val analytics = state.analytics

    LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("time_range") {
            TimeRangeSelector(
                selected = state.selectedTimeRange,
                onSelected = onTimeRangeSelected,
            )
        }

        item("summary_stats") {
            SummaryStatsRow(analytics = analytics)
        }

        if (analytics != null && analytics.totalTokens > 0) {
            item("token_breakdown") {
                TokenBreakdownRow(analytics = analytics)
            }
        }

        item("chart_by_model") {
            ChartCard(
                title = stringResource(R.string.screen_usage_tokens_by_model),
                values = analytics?.modelBreakdowns?.map { it.totalTokens } ?: emptyList(),
                labels = analytics?.modelBreakdowns?.map { truncateModel(it.model) } ?: emptyList(),
            )
        }

        item("chart_over_time") {
            ChartCard(
                title = stringResource(R.string.screen_usage_usage_over_time),
                values = analytics?.timeBuckets?.map { it.totalTokens } ?: emptyList(),
                labels = analytics?.timeBuckets?.map { it.label } ?: emptyList(),
            )
        }

        item("chart_by_agent") {
            ChartCard(
                title = stringResource(R.string.screen_usage_tokens_by_agent),
                values = analytics?.agentBreakdowns?.map { it.totalTokens } ?: emptyList(),
                labels = analytics?.agentBreakdowns?.map { it.agentName } ?: emptyList(),
            )
        }

        item("recent_runs_header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.screen_usage_recent_runs),
                    style = MaterialTheme.typography.titleMedium,
                )
                TextButton(onClick = onNavigateToRuns) {
                    Text(stringResource(R.string.screen_usage_view_all))
                }
            }
        }

        if (state.recentRuns.isEmpty()) {
            item("recent_runs_empty") {
                Text(
                    text = stringResource(R.string.screen_home_usage_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        } else {
            items(state.recentRuns, key = { it.id }) { run ->
                RunSummaryCard(run = run)
            }
        }

        item("bottom_spacer") { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun TimeRangeSelector(
    selected: TimeRange,
    onSelected: (TimeRange) -> Unit,
) {
    val ranges = TimeRange.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        ranges.forEachIndexed { index, range ->
            SegmentedButton(
                selected = range == selected,
                onClick = { onSelected(range) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = ranges.size),
            ) {
                Text(range.label)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryStatsRow(analytics: UsageAnalytics?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatMiniCard(
            label = stringResource(R.string.screen_usage_total_tokens),
            value = formatCompact(analytics?.totalTokens ?: 0),
            icon = LettaIcons.Database,
            modifier = Modifier.weight(1f),
        )
        StatMiniCard(
            label = stringResource(R.string.screen_usage_total_runs),
            value = formatCompact(analytics?.totalRuns ?: 0),
            icon = LettaIcons.Play,
            modifier = Modifier.weight(1f),
        )
        StatMiniCard(
            label = stringResource(R.string.screen_usage_avg_latency),
            value = "${formatCompact((analytics?.averageLatencyMs ?: 0).toInt())}ms",
            icon = LettaIcons.AccessTime,
            modifier = Modifier.weight(1f),
        )
        StatMiniCard(
            label = stringResource(R.string.screen_usage_errors),
            value = (analytics?.errorCount ?: 0).toString(),
            icon = LettaIcons.Error,
            modifier = Modifier.weight(1f),
            isError = (analytics?.errorCount ?: 0) > 0,
        )
    }
}

@Composable
private fun StatMiniCard(
    label: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) {
    val accentColors = MaterialTheme.customColors
    val containerColor = if (isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        accentColors.freshAccentContainer
    }
    val contentColor = if (isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.height(16.dp),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = contentColor,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TokenBreakdownRow(analytics: UsageAnalytics) {
    val accentColors = MaterialTheme.customColors
    Card(
        colors = CardDefaults.cardColors(
            containerColor = accentColors.freshAccentContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.screen_usage_token_breakdown),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TokenChip(stringResource(R.string.screen_usage_prompt_tokens), analytics.promptTokens)
                TokenChip(stringResource(R.string.screen_usage_completion_tokens), analytics.completionTokens)
                TokenChip(stringResource(R.string.screen_usage_cached_tokens), analytics.cachedTokens)
                TokenChip(stringResource(R.string.screen_usage_reasoning_tokens), analytics.reasoningTokens)
            }
        }
    }
}

@Composable
private fun TokenChip(label: String, count: Int) {
    Column {
        Text(
            text = formatCompact(count),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChartCard(
    title: String,
    values: List<Int>,
    labels: List<String>,
) {
    val hasData = values.any { it > 0 }
    val accentColors = MaterialTheme.customColors
    val primaryColor = accentColors.freshAccent

    Card(
        colors = CardDefaults.cardColors(
            containerColor = accentColors.freshAccentContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(12.dp))

            if (!hasData) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.screen_usage_no_chart_data),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val modelProducer = remember { CartesianChartModelProducer() }
                val chartLabels = remember(labels) { labels.toList() }

                LaunchedEffect(values) {
                    modelProducer.runTransaction {
                        columnSeries { series(values) }
                    }
                }

                val colorArgb = primaryColor.toArgb()

                CartesianChartHost(
                    chart = rememberCartesianChart(
                        rememberColumnCartesianLayer(
                            ColumnCartesianLayer.ColumnProvider.series(
                                rememberLineComponent(fill = Fill(colorArgb)),
                            ),
                        ),
                        startAxis = VerticalAxis.rememberStart(),
                        bottomAxis = HorizontalAxis.rememberBottom(
                            valueFormatter = { _, value, _ ->
                                chartLabels.getOrNull(value.toInt()) ?: ""
                            },
                        ),
                    ),
                    modelProducer = modelProducer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                )
            }
        }
    }
}

@Composable
private fun RunSummaryCard(run: RunSummary) {
    val accentColors = MaterialTheme.customColors
    val containerColor = if (run.hasError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        accentColors.freshAccentContainer
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = run.agentName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                StatusChip(status = run.status)
            }

            Text(
                text = run.model,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.screen_usage_tokens_label, formatCompact(run.totalTokens)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                run.durationMs?.let { ms ->
                    Text(
                        text = stringResource(R.string.screen_usage_duration_label, formatCompact(ms.toInt())),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: String) {
    val (bgColor, textColor) = when (status) {
        "completed" -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        "error" -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        "running" -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = status,
        style = MaterialTheme.typography.labelSmall,
        color = textColor,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun formatCompact(value: Int): String = when {
    value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 10_000 -> String.format(Locale.US, "%.1fK", value / 1_000.0)
    value >= 1_000 -> String.format(Locale.US, "%,d", value)
    else -> value.toString()
}

private fun truncateModel(model: String): String {
    val lastSlash = model.lastIndexOf('/')
    return if (lastSlash >= 0 && lastSlash < model.length - 1) model.substring(lastSlash + 1) else model
}
