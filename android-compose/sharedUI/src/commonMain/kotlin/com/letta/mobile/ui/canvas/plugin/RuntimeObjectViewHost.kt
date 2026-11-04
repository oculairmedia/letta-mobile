package com.letta.mobile.ui.canvas.plugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.letta.mobile.data.plugin.PluginRegistryState
import com.letta.mobile.data.plugin.runtimeview.RuntimeViewFallback
import com.letta.mobile.data.plugin.runtimeview.RuntimeViewObject

/**
 * Neutral mount using the existing viewer registry. The host resolves canonical data and owns the
 * mount; trusted implementations receive only a read-only snapshot, selection and failure callback.
 * A reported failure stays in fallback until replacement or reconfiguration. Trusted Compose code
 * must report recoverable failures; this is not an exception sandbox or a runtime code loader.
 */
@Composable
fun RuntimeObjectViewHost(
    mountId: String,
    value: RuntimeViewObject?,
    state: PluginRegistryState,
    modifier: Modifier = Modifier,
    fallback: @Composable (RuntimeViewFallback, Modifier) -> Unit,
) {
    val binding = state.viewBinding(mountId)
    val view = LocalCanvasDocumentViewers.current.runtimeViews.firstOrNull { it.descriptor.id == binding?.viewId }
    val unavailable = state.viewFallback(binding, value, view?.descriptor)
    key(mountId, view, binding, value?.reference) {
        var failure by remember { mutableStateOf<String?>(null) }
        val reason = unavailable ?: failure?.let { RuntimeViewFallback.FAILED }
        if (reason != null) fallback(reason, modifier)
        else checkNotNull(view).render(checkNotNull(value), checkNotNull(binding), modifier) { failure = it }
    }
}
