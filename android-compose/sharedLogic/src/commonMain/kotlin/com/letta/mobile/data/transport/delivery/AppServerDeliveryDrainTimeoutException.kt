package com.letta.mobile.data.transport.delivery

/** A socket closed before its bounded inbound delivery queues drained. */
internal class AppServerDeliveryDrainTimeoutException(
    timeoutMillis: Long,
) : IllegalStateException(
    "App Server delivery queues did not drain within ${timeoutMillis}ms after socket EOF",
)
