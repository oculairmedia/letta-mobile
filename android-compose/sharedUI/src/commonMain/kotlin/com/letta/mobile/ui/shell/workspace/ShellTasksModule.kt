package com.letta.mobile.ui.shell.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.repository.api.ISubagentRepository
import com.letta.mobile.data.repository.api.SubagentParentScope
import com.letta.mobile.data.repository.api.dismissAction
import com.letta.mobile.data.subagents.visibleRunningCount
import com.letta.mobile.ui.chat.subagents.BackgroundTasksPanel
import com.letta.mobile.ui.chat.subagents.backgroundTasksLabel
import com.letta.mobile.ui.shell.modules.UnavailableModule
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** The test tag of a tasks module listing the open conversation's background tasks. */
internal const val SHELL_TASKS_MODULE_TAG = "shell-tasks-module"

/**
 * Where a tasks module placed anywhere in the shell lists background tasks: the host's subagent
 * registry. Null (the default) is a host that cannot list them, whose tasks modules show as
 * unavailable.
 */
val LocalShellTaskRegistry = staticCompositionLocalOf<ISubagentRepository?> { null }

/** Lets every tasks module inside [content] list and dismiss background tasks through [registry]. */
@Composable
fun ProvideShellTaskRegistry(registry: ISubagentRepository?, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalShellTaskRegistry provides registry, content = content)
}

/**
 * The background tasks of the open conversation [scope] as a shell module, at whatever size its
 * place gives it: the shared [BackgroundTasksPanel] over the host's [LocalShellTaskRegistry], under a
 * title that counts the running ones. Dismiss is the registry's durable dismissal for that
 * conversation, so a dismissed task stays gone when the registry fetches again (a reconnect) or the
 * module is shown again. No conversation open shows the panel's empty state; a host without a
 * registry shows the shell's unavailable module.
 */
@Composable
internal fun ShellTasksModule(scope: SubagentParentScope?, modifier: Modifier) {
    val registry = LocalShellTaskRegistry.current
    if (registry == null) {
        UnavailableModule(modifier)
        return
    }
    val tasks by remember(registry, scope) { registry.tasksOf(scope) }
        .collectAsState(remember(registry, scope) { scope?.let(registry::currentSubagentsWithHistory).orEmpty() })
    val dismiss = remember(registry, scope) { registry.dismissAction(scope) }
    Column(modifier.background(MaterialTheme.colorScheme.surfaceContainerLow).testTag(SHELL_TASKS_MODULE_TAG)) {
        Text(
            backgroundTasksLabel(tasks.visibleRunningCount()),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(LettaDimens.Space.lg),
        )
        BackgroundTasksPanel(subagents = tasks, dismissTask = dismiss)
    }
}

/** The live tasks of [scope], or none while no conversation is open. */
private fun ISubagentRepository.tasksOf(scope: SubagentParentScope?): Flow<List<SubagentEntry>> =
    scope?.let(::subagentsWithHistoryFlow) ?: flowOf(emptyList())
