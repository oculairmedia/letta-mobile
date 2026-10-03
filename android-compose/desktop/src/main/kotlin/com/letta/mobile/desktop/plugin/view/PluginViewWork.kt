package com.letta.mobile.desktop.plugin.view

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Where a live view's work runs: in the [scope] of whoever shows the views (the desktop window's
 * composition, which outlives every board in it, so a teardown started when a board closes still
 * finishes), off the UI thread on [dispatcher].
 */
internal class PluginViewWork(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    fun launch(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(dispatcher, block = block)
}
