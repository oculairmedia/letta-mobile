package com.letta.mobile.data.repository.modelcontrol

/** A browser page cannot call the providers' token endpoints (CORS); the terminal steps remain. */
actual fun defaultProviderOAuthHttp(): ProviderOAuthHttp? = null
