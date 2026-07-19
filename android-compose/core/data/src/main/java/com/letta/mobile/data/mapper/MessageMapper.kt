package com.letta.mobile.data.mapper

import com.letta.mobile.data.model.AppMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.UiMessage

/** Public mapping facade retained for callers while implementations stay direction-focused. */
fun List<LettaMessage>.toAppMessages(): List<AppMessage> = mapToAppMessages()

fun LettaMessage.toAppMessage(): AppMessage? = mapToAppMessage(MessageMappingState())

fun LettaMessage.toAppMessage(state: MessageMappingState): AppMessage? = mapToAppMessage(state)

fun List<AppMessage>.toUiMessages(): List<UiMessage> = mapToUiMessages()

fun AppMessage.toUiMessage(): UiMessage = mapToUiMessage()
