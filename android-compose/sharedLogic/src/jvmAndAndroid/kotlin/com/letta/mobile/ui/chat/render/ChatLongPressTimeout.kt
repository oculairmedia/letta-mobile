package com.letta.mobile.ui.chat.render

/**
 * Multiplier applied to the platform long-press timeout to compute the chat
 * timeline long-press threshold. A 2× multiplier reduces false positives
 * from incidental touches during scrolling.
 */
const val CHAT_LONG_PRESS_TIMEOUT_MULTIPLIER: Long = 2L

/**
 * The long-press hold duration (ms) used by chat-timeline long-press handlers,
 * derived from the platform [viewConfiguration]'s long-press timeout scaled by
 * [CHAT_LONG_PRESS_TIMEOUT_MULTIPLIER].
 */
fun chatLongPressTimeoutMillis(platformLongPressTimeoutMillis: Long): Long =
    platformLongPressTimeoutMillis * CHAT_LONG_PRESS_TIMEOUT_MULTIPLIER
