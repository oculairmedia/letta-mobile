package com.letta.mobile.data.repository.modelcontrol

/** The host-native build ships no UI and no HTTPS engine for sign-ins. */
actual fun defaultProviderOAuthHttp(): ProviderOAuthHttp? = null
