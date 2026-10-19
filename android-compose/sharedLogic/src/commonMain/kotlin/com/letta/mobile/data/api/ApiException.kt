package com.letta.mobile.data.api

/**
 * HTTP-status failure from a Letta API call. Platform-neutral so feature
 * code and `mapErrorToUserMessage` can see it without the Android data layer.
 */
open class ApiException(val code: Int, message: String) : Exception(message)
