package com.letta.mobile.ui.secrets

import androidx.compose.runtime.Composable
import com.letta.mobile.data.secrets.client.SecureSurfaceCapabilities

/**
 * Desktop (Compose for desktop on the JVM): there is no screenshot or screen-recording block.
 * Compose for desktop offers no window flag for it, and the AWT window it draws into has none
 * either, so any screen capture shows the form. What desktop does guarantee comes from the shared
 * form itself ([com.letta.mobile.data.secrets.client.SecureProtection.MASKED_FIELD_STATE]): the
 * field's own text is mask characters only, so copy, cut, drag-and-drop, the OS clipboard and its
 * history, and accessibility readers never receive the value.
 *
 * Not available: OS autofill and input-method learning have no hook here (a desktop input method
 * gets the typed characters like any other field), and the value is visible on screen while the
 * explicit reveal toggle is on. Answers [SecureSurfaceCapabilities.SharedOnly] and changes nothing.
 */
@Composable
actual fun SecureSurfaceEffect(): SecureSurfaceCapabilities = SecureSurfaceCapabilities.SharedOnly

/** Desktop: no input method hook exists, so this only draws [content]. */
@Composable
actual fun SecureTextInput(content: @Composable () -> Unit) = content()
