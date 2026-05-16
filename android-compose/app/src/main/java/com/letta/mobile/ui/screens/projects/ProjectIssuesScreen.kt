package com.letta.mobile.ui.screens.projects

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.data.model.ProjectIssueDetail
import com.letta.mobile.data.model.ProjectIssueSummary
import com.letta.mobile.ui.common.LocalSnackbarDispatcher
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.components.ActionSheet
import com.letta.mobile.ui.components.ActionSheetItem
import com.letta.mobile.ui.components.CardGroup
import com.letta.mobile.ui.components.EmptyState
import com.letta.mobile.ui.components.ErrorContent
import com.letta.mobile.ui.components.ExpandableTitleSearch
import com.letta.mobile.ui.components.LettaCardDefaults
import com.letta.mobile.ui.components.ShimmerBox
import com.letta.mobile.ui.components.TextInputDialog
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaSpacing
import com.letta.mobile.util.formatRelativeTime
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.columnSeries
import com.patrykandpatrick.vico.compose.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.pushpal.jetlime.EventPointType
import com.pushpal.jetlime.ItemsList
import com.pushpal.jetlime.JetLimeColumn
import com.pushpal.jetlime.JetLimeEvent
import com.pushpal.jetlime.JetLimeEventDefaults

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectIssuesScreen(
    onNavigateBack: () -> Unit,
    onNavigateToIssue: (issueId: String) -> Unit,
    viewModel: ProjectIssuesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarDispatcher.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var isSearchExpanded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ProjectIssuesUiEvent.ShowMessage -> snackbar.dispatch(event.message)
            }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = com.letta.mobile.ui.theme.LettaTopBarDefaults.scaffoldContainerColor(),
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    ExpandableTitleSearch(
                        query = (uiState as? UiState.Success)?.data?.searchQuery.orEmpty(),
                        onQueryChange = viewModel::updateSearchQuery,
                        onClear = { viewModel.updateSearchQuery("") },
                        expanded = isSearchExpanded,
                        onExpandedChange = { isSearchExpanded = it },
                        placeholder = stringResource(R.string.screen_project_issues_search_hint),
                        openSearchContentDescription = stringResource(R.string.action_search),
                        closeSearchContentDescription = stringResource(R.string.action_close),
                        titleContent = {
                            Column {
                                Text(stringResource(R.string.screen_project_issues_title))
                                (uiState as? UiState.Success)?.data?.projectName?.let { name ->
                                    Text(
                                        text = name,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        },
                    )
                },
                scrollBehavior = scrollBehavior,
                colors = com.letta.mobile.ui.theme.LettaTopBarDefaults.largeTopAppBarColors(),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(LettaIcons.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(LettaIcons.Refresh, stringResource(R.string.action_refresh))
                    }
                },
            )
        },
    ) { paddingValues ->
        when (val state = uiState) {
            is UiState.Loading -> ProjectIssuesLoading(modifier = Modifier.padding(paddingValues))
            is UiState.Error -> ErrorContent(
                message = state.message,
                onRetry = viewModel::refresh,
                modifier = Modifier.padding(paddingValues),
            )
            is UiState.Success -> {
                val filteredIssues = remember(state.data.issues, state.data.searchQuery, state.data.selectedStatus) {
                    viewModel.filteredIssues()
                }
                val filteredReadyWork = remember(state.data.readyWork, state.data.searchQuery, state.data.selectedStatus) {
                    viewModel.filteredReadyWork()
                }
                val listState = rememberLazyListState()
                // Track the issue currently at the top of the viewport so the
                // (collapsed) completed-timeline card can highlight the matching
                // entry when the user expands it. Items in the LazyColumn use
                // either the raw `issue.id` (All section) or `"ready-${id}"`
                // (Ready section); anything else is non-issue chrome.
                val highlightedIssueId by remember(filteredIssues, filteredReadyWork) {
                    derivedStateOf {
                        val visibleKeys = listState.layoutInfo.visibleItemsInfo.asSequence()
                        visibleKeys
                            .mapNotNull { it.key as? String }
                            .map { key -> if (key.startsWith("ready-")) key.removePrefix("ready-") else key }
                            .firstOrNull { id ->
                                filteredIssues.any { it.id == id } || filteredReadyWork.any { it.id == id }
                            }
                    }
                }
                PullToRefreshBox(
                    isRefreshing = state.data.isRefreshing,
                    onRefresh = viewModel::refresh,
                    modifier = Modifier
                        .padding(paddingValues)
                        .fillMaxSize(),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(LettaSpacing.screenHorizontal),
                        verticalArrangement = Arrangement.spacedBy(LettaSpacing.cardGap),
                    ) {
                        item {
                            IssueFilterRow(
                                selectedStatus = state.data.selectedStatus,
                                onStatusSelected = viewModel::selectStatus,
                            )
                        }
                        item {
                            ProjectIssueCreationChartCard(
                                buckets = state.data.creationBuckets,
                                summary = state.data.analyticsSummary,
                                notice = state.data.analyticsNotice,
                            )
                        }
                        item {
                            ProjectIssueCompletedTimelineCard(
                                items = state.data.completedTimeline,
                                isPartial = state.data.analyticsIsPartial,
                                completionSource = state.data.analyticsCompletionSource,
                                hasMore = state.data.timelineHasMore,
                                highlightedIssueId = highlightedIssueId,
                                onIssueClick = onNavigateToIssue,
                            )
                        }
                        if (filteredReadyWork.isNotEmpty()) {
                            item {
                                Text(
                                    text = stringResource(R.string.screen_project_issues_ready_section),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            items(filteredReadyWork, key = { "ready-${it.id}" }) { issue ->
                                ProjectIssueCard(issue = issue, onClick = { onNavigateToIssue(issue.id) })
                            }
                        }
                        item {
                            Text(
                                text = stringResource(R.string.screen_project_issues_all_section),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (filteredIssues.isEmpty()) {
                            item {
                                EmptyState(
                                    icon = LettaIcons.ListIcon,
                                    message = if (state.data.searchQuery.isBlank()) {
                                        stringResource(R.string.screen_project_issues_empty)
                                    } else {
                                        stringResource(R.string.screen_project_issues_empty_search, state.data.searchQuery)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        } else {
                            itemsIndexed(filteredIssues, key = { _, issue -> issue.id }) { index, issue ->
                                if (index == filteredIssues.lastIndex && state.data.hasMoreIssues && state.data.isLoadingMoreIssues.not()) {
                                    LaunchedEffect(issue.id, state.data.hasMoreIssues) {
                                        viewModel.loadMoreIssues()
                                    }
                                }
                                ProjectIssueCard(issue = issue, onClick = { onNavigateToIssue(issue.id) })
                            }
                            if (state.data.isLoadingMoreIssues) {
                                item { ShimmerBox(height = 104.dp, widthFraction = 1f) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectIssueDetailScreen(
    onNavigateBack: () -> Unit,
    viewModel: ProjectIssueDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarDispatcher.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ProjectIssuesUiEvent.ShowMessage -> snackbar.dispatch(event.message)
            }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = com.letta.mobile.ui.theme.LettaTopBarDefaults.scaffoldContainerColor(),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.screen_project_issue_detail_title)) },
                scrollBehavior = scrollBehavior,
                colors = com.letta.mobile.ui.theme.LettaTopBarDefaults.largeTopAppBarColors(),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(LettaIcons.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(LettaIcons.Refresh, stringResource(R.string.action_refresh))
                    }
                    IconButton(onClick = { viewModel.showActions(true) }) {
                        Icon(LettaIcons.MoreVert, stringResource(R.string.action_more))
                    }
                },
            )
        },
    ) { paddingValues ->
        when (val state = uiState) {
            is UiState.Loading -> ProjectIssuesLoading(modifier = Modifier.padding(paddingValues))
            is UiState.Error -> ErrorContent(
                message = state.message,
                onRetry = viewModel::refresh,
                modifier = Modifier.padding(paddingValues),
            )
            is UiState.Success -> {
                LazyColumn(
                    modifier = Modifier
                        .padding(paddingValues)
                        .fillMaxSize(),
                    contentPadding = PaddingValues(LettaSpacing.screenHorizontal),
                    verticalArrangement = Arrangement.spacedBy(LettaSpacing.cardGap),
                ) {
                    item { ProjectIssueHeader(issue = state.data.issue) }
                    item { ProjectIssueBody(issue = state.data.issue) }
                    if (state.data.issue.notes.isNotEmpty() || state.data.issue.comments.isNotEmpty()) {
                        item { ProjectIssueNotes(issue = state.data.issue) }
                    }
                }

                ActionSheet(
                    show = state.data.showActions,
                    onDismiss = { viewModel.showActions(false) },
                    title = state.data.issue.id,
                ) {
                    val issue = state.data.issue
                    if (issue.assignee.isNullOrBlank()) {
                        ActionSheetItem(
                            text = stringResource(R.string.screen_project_issue_claim_action),
                            icon = LettaIcons.Play,
                            onClick = viewModel::claimIssue,
                        )
                    } else {
                        ActionSheetItem(
                            text = stringResource(R.string.screen_project_issue_unclaim_action),
                            icon = LettaIcons.Close,
                            onClick = viewModel::unclaimIssue,
                        )
                    }
                    ActionSheetItem(
                        text = stringResource(R.string.screen_project_issue_add_note_action),
                        icon = LettaIcons.Edit,
                        onClick = { viewModel.showNoteDialog(true) },
                    )
                    if (issue.status.equals("closed", ignoreCase = true)) {
                        ActionSheetItem(
                            text = stringResource(R.string.screen_project_issue_reopen_action),
                            icon = LettaIcons.Refresh,
                            onClick = viewModel::reopenIssue,
                        )
                    } else {
                        ActionSheetItem(
                            text = stringResource(R.string.screen_project_issue_close_action),
                            icon = LettaIcons.Check,
                            onClick = viewModel::closeIssue,
                            destructive = true,
                        )
                    }
                }

                TextInputDialog(
                    show = state.data.showNoteDialog,
                    title = stringResource(R.string.screen_project_issue_add_note_action),
                    label = stringResource(R.string.screen_project_issue_note_label),
                    confirmText = stringResource(R.string.action_save),
                    dismissText = stringResource(R.string.action_cancel),
                    onConfirm = viewModel::addNote,
                    onDismiss = { viewModel.showNoteDialog(false) },
                    singleLine = false,
                    minLines = 3,
                )
            }
        }
    }
}

@Composable
private fun ProjectIssuesLoading(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(LettaSpacing.screenHorizontal),
        verticalArrangement = Arrangement.spacedBy(LettaSpacing.cardGap),
    ) {
        repeat(5) { ShimmerBox(height = 104.dp, widthFraction = 1f) }
    }
}

@Composable
private fun IssueFilterRow(
    selectedStatus: String?,
    onStatusSelected: (String?) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val statuses = listOf(null, "open", "in_progress", "closed")
        statuses.forEach { status ->
            FilterChip(
                selected = selectedStatus == status,
                onClick = { onStatusSelected(status) },
                label = { Text(status?.toIssueLabel() ?: stringResource(R.string.screen_project_issues_filter_all)) },
            )
        }
    }
}

@Composable
private fun ProjectIssueCreationChartCard(
    buckets: List<ProjectIssueCreationBucket>,
    summary: IssueAnalyticsSummaryUi?,
    notice: String?,
    modifier: Modifier = Modifier,
) {
    val values = remember(buckets) { buckets.map { it.count } }
    val labels = remember(buckets) { buckets.map { it.label } }
    val labelStride = remember(labels) { (labels.size / 6).coerceAtLeast(1) }
    val barColor = MaterialTheme.colorScheme.primary

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.screen_project_issues_created_chart_title),
                style = MaterialTheme.typography.titleMedium,
            )
            summary?.let {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    IssueMetaChip(stringResource(R.string.screen_project_issues_analytics_created_total, it.totalCreatedInRange))
                    IssueMetaChip(stringResource(R.string.screen_project_issues_analytics_completed_total, it.totalCompletedInRange))
                }
            }
            notice?.let { message ->
                AssistChip(
                    onClick = {},
                    label = { Text(message) },
                )
            }

            if (values.isEmpty() || values.all { it == 0 }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.screen_project_issues_created_chart_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val modelProducer = remember { CartesianChartModelProducer() }

                LaunchedEffect(values) {
                    modelProducer.runTransaction {
                        columnSeries { series(values) }
                    }
                }

                // letta-mobile: Vico 3.1.0 rejects blank strings from
                // CartesianValueFormatter -- use HorizontalAxis.ItemPlacer to
                // control which x-values get labeled instead, and have the
                // formatter always return a real string. (Previously the
                // formatter returned "" for non-stride indexes, which crashed
                // the issue-tracker screen on render.)
                val bottomAxisItemPlacer = remember(labels, labelStride) {
                    HorizontalAxis.ItemPlacer.aligned(spacing = { labelStride })
                }
                CartesianChartHost(
                    chart = rememberCartesianChart(
                        rememberColumnCartesianLayer(
                            ColumnCartesianLayer.ColumnProvider.series(
                                rememberLineComponent(fill = Fill(barColor)),
                            ),
                        ),
                        startAxis = VerticalAxis.rememberStart(),
                        bottomAxis = HorizontalAxis.rememberBottom(
                            valueFormatter = { _, value, _ ->
                                val index = value.toInt()
                                labels.getOrNull(index) ?: index.toString()
                            },
                            itemPlacer = bottomAxisItemPlacer,
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
private fun ProjectIssueCompletedTimelineCard(
    items: List<ProjectIssueTimelineItem>,
    isPartial: Boolean,
    completionSource: String?,
    hasMore: Boolean,
    highlightedIssueId: String?,
    onIssueClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // letta-mobile: collapsed by default to match the tool-call card pattern
    // while keeping project-screen motion local to the app module.
    var expanded by rememberSaveable(items) { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = projectTimelineChipCrossfadeSpec,
        label = "ProjectTimelineChevronRotation",
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = LettaCardDefaults.listContainerColor,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = items.isNotEmpty()) { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.screen_project_issues_completed_timeline_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (items.isNotEmpty()) {
                    Text(
                        text = if (expanded) {
                            stringResource(R.string.action_collapse)
                        } else {
                            stringResource(R.string.screen_project_issues_timeline_count, items.size)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Icon(
                        imageVector = LettaIcons.ExpandMore,
                        contentDescription = if (expanded) {
                            stringResource(R.string.action_collapse)
                        } else {
                            stringResource(R.string.action_expand)
                        },
                        modifier = Modifier
                            .size(20.dp)
                            .rotate(chevronRotation),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (isPartial && completionSource == "issue_close_metadata") {
                IssueMetaChip(stringResource(R.string.screen_project_issues_completion_metadata_partial))
            }

            if (items.isEmpty()) {
                EmptyState(
                    icon = LettaIcons.CheckCircle,
                    message = stringResource(R.string.screen_project_issues_completed_timeline_empty),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                AnimatedVisibility(
                    visible = expanded,
                    enter = projectTimelineExpandEnter(),
                    exit = projectTimelineExpandExit(),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val timelineHeight = ((items.size.coerceAtMost(4) * 96) + 24).dp
                        val pointFillColor = MaterialTheme.colorScheme.tertiary
                        val pointColor = MaterialTheme.colorScheme.tertiaryContainer
                        val pointStrokeColor = MaterialTheme.colorScheme.onTertiaryContainer
                        // Highlight uses the primary container so it visually
                        // distinguishes from the tertiary timeline accent
                        // without clashing.
                        val highlightPointFill = MaterialTheme.colorScheme.primary
                        val highlightPointColor = MaterialTheme.colorScheme.primaryContainer
                        val highlightStroke = MaterialTheme.colorScheme.onPrimaryContainer

                        JetLimeColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(timelineHeight),
                            itemsList = ItemsList(items),
                            key = { _, item -> item.id },
                        ) { _, item, position ->
                            val isHighlighted = highlightedIssueId == item.id
                            JetLimeEvent(
                                style = JetLimeEventDefaults.eventStyle(
                                    position = position,
                                    pointColor = if (isHighlighted) highlightPointColor else pointColor,
                                    pointFillColor = if (isHighlighted) highlightPointFill else pointFillColor,
                                    pointRadius = if (isHighlighted) 12.dp else 10.dp,
                                    pointStrokeColor = if (isHighlighted) highlightStroke else pointStrokeColor,
                                    pointStrokeWidth = if (isHighlighted) 2.dp else 1.dp,
                                    pointType = EventPointType.filled(1f),
                                ),
                            ) {
                                ProjectIssueTimelineEvent(
                                    item = item,
                                    highlighted = isHighlighted,
                                    onClick = { onIssueClick(item.id) },
                                )
                            }
                        }
                        if (hasMore) {
                            Text(
                                text = stringResource(R.string.screen_project_issues_timeline_more_available),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

private val projectTimelineChipCrossfadeSpec =
    tween<Float>(durationMillis = 150, easing = FastOutSlowInEasing)

private fun projectTimelineExpandEnter() =
    fadeIn(animationSpec = tween(durationMillis = 190, easing = LinearOutSlowInEasing)) +
        expandVertically(
            animationSpec = tween(durationMillis = 190, easing = LinearOutSlowInEasing),
            expandFrom = Alignment.Top,
        )

private fun projectTimelineExpandExit() =
    fadeOut(animationSpec = tween(durationMillis = 90, easing = FastOutLinearInEasing)) +
        shrinkVertically(
            animationSpec = tween(durationMillis = 130, easing = FastOutLinearInEasing),
            shrinkTowards = Alignment.Top,
        )

@Composable
private fun ProjectIssueTimelineEvent(
    item: ProjectIssueTimelineItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (highlighted) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        tonalElevation = if (highlighted) 3.dp else 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.screen_project_issues_completed_at, formatRelativeTime(item.completedAt)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                IssueMetaChip(item.id)
                IssueMetaChip(item.statusLabel)
                item.priority?.let { IssueMetaChip(it.uppercase()) }
                item.type?.let { IssueMetaChip(it) }
            }
        }
    }
}

@Composable
private fun ProjectIssueCard(
    issue: ProjectIssueSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = LettaCardDefaults.listContainerColor,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(issue.id, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(issue.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                IssueStatusChip(issue.statusLabel ?: issue.status, issue.ready)
            }
            issue.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                issue.priority?.let { IssueMetaChip(it.uppercase()) }
                issue.type?.let { IssueMetaChip(it) }
                issue.assignee?.takeIf { it.isNotBlank() }?.let { IssueMetaChip(it) }
                if (issue.isBlocked) IssueMetaChip(stringResource(R.string.screen_project_issues_blocked_label), isWarning = true)
            }
        }
    }
}

@Composable
private fun ProjectIssueHeader(issue: ProjectIssueDetail) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = LettaCardDefaults.listContainerColor,
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(issue.id, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(issue.title, style = MaterialTheme.typography.headlineSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                IssueStatusChip(issue.statusLabel ?: issue.status, issue.ready)
                issue.priority?.let { IssueMetaChip(it.uppercase()) }
                issue.type?.let { IssueMetaChip(it) }
                issue.assignee?.takeIf { it.isNotBlank() }?.let { IssueMetaChip(it) }
            }
        }
    }
}

@Composable
private fun ProjectIssueBody(issue: ProjectIssueDetail) {
    CardGroup(title = { Text(stringResource(R.string.common_details)) }) {
        issue.description?.takeIf { it.isNotBlank() }?.let { description ->
            item(
                overlineContent = { Text(stringResource(R.string.common_description)) },
                supportingContent = { Text(description) },
                headlineContent = { Text(issue.summary ?: issue.title) },
            )
        }
        if (issue.acceptanceCriteria.isNotEmpty()) {
            item(
                overlineContent = { Text(stringResource(R.string.screen_project_issue_acceptance_title)) },
                supportingContent = { Text(issue.acceptanceCriteria.joinToString(separator = "\n") { "• $it" }) },
                headlineContent = { Text(stringResource(R.string.screen_project_issue_acceptance_summary, issue.acceptanceCriteria.size)) },
            )
        }
        issue.updatedAt?.let { updatedAt ->
            item(
                overlineContent = { Text(stringResource(R.string.common_updated)) },
                headlineContent = { Text(formatRelativeTime(updatedAt)) },
            )
        }
        if (issue.blockedBy.isNotEmpty()) {
            item(
                overlineContent = { Text(stringResource(R.string.screen_project_issues_blocked_label)) },
                supportingContent = { Text(issue.blockedBy.joinToString { it.id }) },
                headlineContent = { Text(stringResource(R.string.screen_project_issue_blocked_by_summary, issue.blockedBy.size)) },
            )
        }
    }
}

@Composable
private fun ProjectIssueNotes(issue: ProjectIssueDetail) {
    val notes = issue.notes + issue.comments
    CardGroup(title = { Text(stringResource(R.string.screen_project_issue_notes_title)) }) {
        notes.forEach { note ->
            item(
                overlineContent = note.author?.let { author -> { Text(author) } },
                supportingContent = note.text?.let { text -> { Text(text) } },
                headlineContent = { Text(note.createdAt?.let(::formatRelativeTime) ?: stringResource(R.string.common_unknown)) },
            )
        }
    }
}

@Composable
private fun IssueStatusChip(status: String, ready: Boolean) {
    val container = when {
        ready -> MaterialTheme.colorScheme.tertiaryContainer
        status.equals("closed", ignoreCase = true) -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val content = when {
        ready -> MaterialTheme.colorScheme.onTertiaryContainer
        status.equals("closed", ignoreCase = true) -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    AssistChip(
        onClick = {},
        label = { Text(if (ready) stringResource(R.string.screen_project_issues_ready_label) else status.toIssueLabel()) },
        leadingIcon = {
            Icon(LettaIcons.Circle, contentDescription = null, modifier = Modifier.size(12.dp), tint = content)
        },
        colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
            containerColor = container,
            labelColor = content,
            leadingIconContentColor = content,
        ),
    )
}

@Composable
private fun IssueMetaChip(value: String, isWarning: Boolean = false) {
    AssistChip(
        onClick = {},
        label = { Text(value.toIssueLabel()) },
        colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
            containerColor = if (isWarning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
            labelColor = if (isWarning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

private fun String.toIssueLabel(): String =
    replace('_', ' ').replaceFirstChar { it.uppercase() }
