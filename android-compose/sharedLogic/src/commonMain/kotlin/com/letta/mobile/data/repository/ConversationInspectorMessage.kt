package com.letta.mobile.data.repository

data class ConversationInspectorMessage(
    val id: String,
    val messageType: String,
    val date: String?,
    val runId: String?,
    val stepId: String?,
    val otid: String?,
    val summary: String,
    val detailLines: List<Pair<String, String>> = emptyList(),
)
