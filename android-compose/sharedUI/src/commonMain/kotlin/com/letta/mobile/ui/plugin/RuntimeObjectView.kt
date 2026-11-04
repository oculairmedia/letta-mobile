package com.letta.mobile.ui.plugin

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.letta.mobile.data.plugin.runtimeview.RuntimeViewBinding
import com.letta.mobile.data.plugin.runtimeview.RuntimeViewDescriptor
import com.letta.mobile.data.plugin.runtimeview.RuntimeViewObject

/** Native, trusted shared renderer. Report recoverable rendering/subscription failures through the failure callback. */
typealias RuntimeObjectViewRenderer = @Composable (
    value: RuntimeViewObject,
    binding: RuntimeViewBinding,
    modifier: Modifier,
    fail: (String) -> Unit,
) -> Unit

/** Registered by host code; document configuration cannot manufacture an implementation. */
class RuntimeObjectView(val descriptor: RuntimeViewDescriptor, val render: RuntimeObjectViewRenderer)

