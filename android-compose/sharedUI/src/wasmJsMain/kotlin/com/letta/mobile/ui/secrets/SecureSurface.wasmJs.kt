package com.letta.mobile.ui.secrets

import androidx.compose.runtime.Composable
import com.letta.mobile.data.secrets.client.SecureSurfaceCapabilities

/**
 * Web (wasm): a documented no-op. A page cannot stop screenshots or screen sharing, and the hidden
 * input element Compose for web types through is created by the framework and is not reachable to
 * set `autocomplete="off"` or a password type on, so browser autofill and input-method learning
 * are whatever the browser does. What the web does guarantee comes from the shared form itself
 * ([com.letta.mobile.data.secrets.client.SecureProtection.MASKED_FIELD_STATE]): the field's own text
 * is mask characters only, so copy, cut and the clipboard never receive the value.
 * Answers [SecureSurfaceCapabilities.SharedOnly]. The web is deferred for this flow's parity.
 */
@Composable
actual fun SecureSurfaceEffect(): SecureSurfaceCapabilities = SecureSurfaceCapabilities.SharedOnly

/** Web: no reachable input element options, so this only draws [content]. */
@Composable
actual fun SecureTextInput(content: @Composable () -> Unit) = content()
