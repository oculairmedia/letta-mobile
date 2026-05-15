package com.letta.mobile.ui.screens.projects

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.BottomAppBarDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.ui.common.LocalSnackbarDispatcher
import com.letta.mobile.ui.components.FormItem
import com.letta.mobile.ui.icons.LettaIcons

private const val PROJECT_CREATED_RESULT_KEY = "create_project.created"

/**
 * letta-mobile-cygd: full-screen replacement for the conversational
 * project-creation `MultiFieldInputDialog` that used to live inside
 * ProjectHomeScreen.
 *
 * Layout choices:
 *   - Persistent bottom bar (Back + primary action) so the next step
 *     stays reachable above the keyboard — `imePadding()` lifts the
 *     whole scaffold cleanly.
 *   - `AnimatedContent` keyed on the step gives a horizontal slide
 *     between steps so direction matches user intent.
 *   - System back, the toolbar back arrow, and the bottom-bar Back
 *     button all share [stepBackOrPop] so the navigation contract is
 *     unambiguous: back through the wizard until step 0, then pop the
 *     route.
 *
 * On successful creation [onProjectCreated] receives the created project
 * name; the caller is responsible for popping back and refreshing
 * `ProjectHomeScreen`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateProjectScreen(
    onNavigateBack: () -> Unit,
    onProjectCreated: (createdProjectName: String) -> Unit,
    viewModel: CreateProjectViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarDispatcher.current

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is CreateProjectEvent.ProjectCreated -> {
                    val tail = if (event.hadGoal) {
                        " Project brief handoff isn't wired yet, so your setup notes stayed local."
                    } else {
                        ""
                    }
                    snackbar.dispatch("Created ${event.name}.$tail")
                    onProjectCreated(event.name)
                }
                is CreateProjectEvent.CreationFailed -> {
                    snackbar.dispatch(event.message)
                }
            }
        }
    }

    val stepBackOrPop = {
        if (!viewModel.goBack()) onNavigateBack()
    }

    BackHandler(enabled = true) { stepBackOrPop() }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.screen_create_project_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = stepBackOrPop) {
                        Icon(
                            imageVector = LettaIcons.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
        bottomBar = {
            CreateProjectBottomBar(
                step = uiState.step,
                isSubmitting = uiState.isSubmitting,
                canAdvance = uiState.draft.normalized().isReadyFor(uiState.step),
                onBack = stepBackOrPop,
                onAdvance = viewModel::advanceOrSubmit,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth(),
        ) {
            CreateProjectStepProgress(uiState.step)

            AnimatedContent(
                targetState = uiState.step,
                transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal
                    val direction = if (forward) {
                        AnimatedContentTransitionScope.SlideDirection.Start
                    } else {
                        AnimatedContentTransitionScope.SlideDirection.End
                    }
                    (slideIntoContainer(direction, animationSpec = tween(280)) + fadeIn(tween(200)))
                        .togetherWith(slideOutOfContainer(direction, animationSpec = tween(280)) + fadeOut(tween(200)))
                },
                label = "create-project-step",
            ) { step ->
                CreateProjectStepContent(
                    step = step,
                    draft = uiState.draft,
                    onDraftChanged = viewModel::updateDraft,
                )
            }
        }
    }
}

@Composable
private fun CreateProjectStepProgress(step: ConversationalProjectStep) {
    val total = ConversationalProjectStep.entries.size.toFloat()
    val progress = (step.ordinal + 1) / total
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateProjectBottomBar(
    step: ConversationalProjectStep,
    isSubmitting: Boolean,
    canAdvance: Boolean,
    onBack: () -> Unit,
    onAdvance: () -> Unit,
) {
    BottomAppBar(
        windowInsets = WindowInsets.navigationBars,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        tonalElevation = BottomAppBarDefaults.ContainerElevation,
    ) {
        val backLabel = stringResource(
            if (step == ConversationalProjectStep.Goal) R.string.action_cancel else R.string.action_back
        )
        val primaryLabel = stringResource(
            if (step == ConversationalProjectStep.Review) R.string.action_create
            else R.string.screen_projects_new_project_conversational_continue
        )

        OutlinedButton(
            onClick = onBack,
            enabled = !isSubmitting,
        ) {
            Text(backLabel)
        }
        Spacer(modifier = Modifier.weight(1f))
        Button(
            onClick = onAdvance,
            enabled = canAdvance && !isSubmitting,
        ) {
            if (isSubmitting) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(primaryLabel)
        }
    }
}

@Composable
private fun CreateProjectStepContent(
    step: ConversationalProjectStep,
    draft: ConversationalProjectDraft,
    onDraftChanged: (ConversationalProjectDraft) -> Unit,
) {
    val scrollState = rememberScrollState()
    val goalLabel = stringResource(R.string.screen_projects_new_project_goal_label)
    val goalHelper = stringResource(R.string.screen_projects_new_project_goal_helper)
    val nameLabel = stringResource(R.string.screen_projects_new_project_name_label)
    val nameHelper = stringResource(R.string.screen_projects_new_project_name_helper)
    val pathLabel = stringResource(R.string.screen_projects_new_project_path_label)
    val pathHelper = stringResource(R.string.screen_projects_new_project_path_helper)
    val pathMissing = stringResource(R.string.screen_projects_settings_path_missing)
    val pathAbsolute = stringResource(R.string.screen_projects_settings_path_must_be_absolute)
    val gitUrlLabel = stringResource(R.string.screen_projects_new_project_git_url_label)
    val gitUrlHelper = stringResource(R.string.screen_projects_new_project_git_url_helper)
    val gitUrlNone = stringResource(R.string.screen_projects_new_project_git_url_none)

    val promptRes = when (step) {
        ConversationalProjectStep.Goal -> R.string.screen_projects_new_project_conversational_goal_prompt
        ConversationalProjectStep.Name -> R.string.screen_projects_new_project_conversational_name_prompt
        ConversationalProjectStep.FilesystemPath -> R.string.screen_projects_new_project_conversational_path_prompt
        ConversationalProjectStep.GitUrl -> R.string.screen_projects_new_project_conversational_git_url_prompt
        ConversationalProjectStep.Review -> R.string.screen_projects_new_project_conversational_review_prompt
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(promptRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (step) {
            ConversationalProjectStep.Goal -> {
                FormItem(
                    label = { Text(goalLabel) },
                    description = { Text(goalHelper) },
                ) {
                    OutlinedTextField(
                        value = draft.goal,
                        onValueChange = { onDraftChanged(draft.copy(goal = it)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                    )
                }
            }

            ConversationalProjectStep.Name -> {
                if (draft.goal.isNotBlank()) {
                    SummaryChip(label = stringResource(R.string.common_description), value = draft.goal)
                }
                FormItem(
                    label = { Text(nameLabel) },
                    description = { Text(nameHelper) },
                ) {
                    OutlinedTextField(
                        value = draft.name,
                        onValueChange = { onDraftChanged(draft.copy(name = it)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            }

            ConversationalProjectStep.FilesystemPath -> {
                val pathValidation = draft.filesystemPathValidation()
                if (draft.goal.isNotBlank()) {
                    SummaryChip(label = stringResource(R.string.common_description), value = draft.goal)
                }
                if (draft.name.isNotBlank()) {
                    SummaryChip(label = nameLabel, value = draft.name)
                }
                FormItem(
                    label = { Text(pathLabel) },
                    description = {
                        Text(
                            when (pathValidation) {
                                ConversationalProjectDraft.FilesystemPathValidation.Missing -> pathMissing
                                ConversationalProjectDraft.FilesystemPathValidation.MustBeAbsolute -> pathAbsolute
                                ConversationalProjectDraft.FilesystemPathValidation.Valid -> pathHelper
                            }
                        )
                    },
                ) {
                    OutlinedTextField(
                        value = draft.filesystemPath,
                        onValueChange = { onDraftChanged(draft.copy(filesystemPath = it)) },
                        modifier = Modifier.fillMaxWidth(),
                        isError = pathValidation != ConversationalProjectDraft.FilesystemPathValidation.Valid,
                        singleLine = true,
                    )
                }
            }

            ConversationalProjectStep.GitUrl -> {
                SummaryChip(label = nameLabel, value = draft.name)
                SummaryChip(label = pathLabel, value = draft.filesystemPath)
                FormItem(
                    label = { Text(gitUrlLabel) },
                    description = { Text(gitUrlHelper) },
                ) {
                    OutlinedTextField(
                        value = draft.gitUrl,
                        onValueChange = { onDraftChanged(draft.copy(gitUrl = it)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            }

            ConversationalProjectStep.Review -> {
                if (draft.goal.isNotBlank()) {
                    SummaryChip(label = goalLabel, value = draft.goal)
                }
                SummaryChip(label = nameLabel, value = draft.name)
                SummaryChip(label = pathLabel, value = draft.filesystemPath)
                SummaryChip(label = gitUrlLabel, value = draft.gitUrl.ifBlank { gitUrlNone })
                Text(
                    text = stringResource(R.string.screen_projects_new_project_conversational_review_helper),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SummaryChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

