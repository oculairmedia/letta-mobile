package com.letta.mobile.ui.chat.surface

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import kotlinx.coroutines.flow.StateFlow

/**
 * letta-mobile-bglj6.1: collects one of the port's flows for the page.
 *
 * Android collects with the host lifecycle (collectAsStateWithLifecycle), so a backgrounded app
 * stops subscribing and the owner's WhileSubscribed flows can stop. Desktop collects for as long
 * as the page is composed: a desktop window has no background state to honour, and
 * collectAsStateWithLifecycle would move collection onto Dispatchers.Main, which the JVM test
 * hosts do not install.
 */
@Composable
internal expect fun <T> StateFlow<T>.collectForChatSurface(): State<T>
