package com.letta.mobile.ui.shell

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * How much workspace chrome floats over the top of the page, measured from the page's top edge:
 * in a compact window drawn inline (the phone, a narrow browser), the status bar and the minimal
 * header's pill, search and menu, which float over the page with no bar of their own. Zero where
 * the header is a row of its own (wide windows, the desktop's title bar).
 *
 * [ShellPage] rests every page below it except the chat container, which draws edge to edge under
 * it and rests its rows, sticky prompt and chat head below it (the chat surface's top chrome inset).
 */
val LocalShellFloatingChromeInset = compositionLocalOf { 0.dp }

/** A page that does not draw under the floating chrome rests below it. */
@Composable
fun Modifier.belowFloatingChrome(): Modifier = padding(top = LocalShellFloatingChromeInset.current)
