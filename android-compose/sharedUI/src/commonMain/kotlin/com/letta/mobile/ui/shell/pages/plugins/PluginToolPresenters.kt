package com.letta.mobile.ui.shell.pages.plugins

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.letta.mobile.data.plugin.contributedBy
import com.letta.mobile.data.plugin.view.PluginViewsModel
import com.letta.mobile.ui.chat.tools.LocalToolPresenters
import com.letta.mobile.ui.chat.tools.ProvideToolPresenters

/**
 * [content] drawing tool calls with the presenters already provided plus the
 * tool and command presenters [model]'s enabled plugins declare; as it is when there is no model.
 */
@Composable
fun ProvidePluginToolPresenters(model: PluginViewsModel?, content: @Composable () -> Unit) {
    if (model == null) return content()
    val plugins by model.plugins.collectAsState()
    val base = LocalToolPresenters.current
    // Worked out once per plugin list: a new registry on every recomposition would recompose every step.
    val registry = remember(base, plugins) { base.contributedBy(plugins.map { it.manifest }) }
    ProvideToolPresenters(registry, content)
}
