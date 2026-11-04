package com.letta.mobile.cli.commands.network

/** Interface state used when choosing routable host addresses. */
internal data class NetworkInterfaceFlags(
    val isUp: Boolean = true,
    val isLoopback: Boolean = false,
    val isPointToPoint: Boolean = false,
)
