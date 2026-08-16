package com.letta.mobile.web.data

data class AgentItemState(
    val id: String,
    val name: String,
    val description: String? = null,
    val model: String = "Unknown model",
    val isOnline: Boolean = true,
)
