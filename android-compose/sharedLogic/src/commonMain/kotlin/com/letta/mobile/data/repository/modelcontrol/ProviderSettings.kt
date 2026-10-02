package com.letta.mobile.data.repository.modelcontrol

/**
 * The Providers settings pages (letta-mobile-w4q4p.6.1): Accounts, API keys and
 * Custom Endpoints, derived from the same `provider.list` rows the Providers &
 * Models pane shows.
 *
 * What the App Server (letta-code 0.32.x `connect_provider`) can actually do,
 * and therefore what these pages offer:
 *  - API-key providers: the host CHECKS the key against the provider before it
 *    saves it, so "connect" is also the validation; there is no separate test.
 *  - Endpoint providers (a `baseUrl` field: OpenAI-compatible, LM Studio): one
 *    connection per provider row. Saving again replaces it; a named second
 *    endpoint is refused (`provider_name` is only accepted with OAuth tokens).
 *  - Subscription (OAuth) providers: the host only accepts finished tokens; it
 *    does not run a browser or device-code login. The working path is the
 *    host's own terminal (`letta`, then `/connect`), which writes the same
 *    local provider store, so the app offers that and a "Check again".
 */

/** How a provider row gets connected. */
enum class ProviderConnectMethod {
    /** Subscription/OAuth: sign in from the host's terminal, then come back. */
    TERMINAL_SIGN_IN,

    /** One or more credential fields (API key, AWS keys or profile). */
    API_KEY,

    /** A base URL (plus an optional key): OpenAI-compatible and local servers. */
    ENDPOINT,

    /** Nothing the app can submit. */
    NONE,
}

const val PROVIDER_FIELD_BASE_URL: String = "baseUrl"

/** True when any auth method asks for a base URL. */
val ConnectableProvider.takesBaseUrl: Boolean
    get() = authMethods.any { method -> method.fields.any { it.key == PROVIDER_FIELD_BASE_URL } }

val ConnectableProvider.connectMethod: ProviderConnectMethod
    get() = when {
        isOauth -> ProviderConnectMethod.TERMINAL_SIGN_IN
        authMethods.isEmpty() -> ProviderConnectMethod.NONE
        takesBaseUrl -> ProviderConnectMethod.ENDPOINT
        else -> ProviderConnectMethod.API_KEY
    }

/** The base URL a connected endpoint uses, when the host reports one. */
val ConnectableProvider.connectedBaseUrl: String?
    get() = connections.firstNotNullOfOrNull { it.baseUrl?.takeIf(String::isNotBlank) }

/** The provider rows of the settings pages, each list sorted by name. */
data class ProviderSettingsLists(
    /** Subscription accounts not connected yet ("Connect an account"). */
    val accounts: List<ConnectableProvider>,
    /** Every connected provider, whatever its method. */
    val connected: List<ConnectableProvider>,
    /** Not connected, connectable from the app with a key or an endpoint. */
    val other: List<ConnectableProvider>,
    /** Providers that take credentials (connected or not). */
    val apiKeys: List<ConnectableProvider>,
    /** Providers that take a base URL (connected or not). */
    val endpoints: List<ConnectableProvider>,
) {
    companion object {
        fun of(providers: List<ConnectableProvider>): ProviderSettingsLists {
            val sorted = providers.distinctBy { it.id }.sortedBy { it.displayName.lowercase() }
            return ProviderSettingsLists(
                accounts = sorted.filter { it.connectMethod == ProviderConnectMethod.TERMINAL_SIGN_IN && !it.isConnected },
                connected = sorted.filter { it.isConnected },
                other = sorted.filter { !it.isConnected && it.canConnectFromApp },
                apiKeys = sorted.filter { it.connectMethod == ProviderConnectMethod.API_KEY },
                endpoints = sorted.filter { it.connectMethod == ProviderConnectMethod.ENDPOINT },
            )
        }
    }
}

/** The provider rows behind the pane's sections (sections without a `provider.list` row are skipped). */
val ProviderManagementState.providers: List<ConnectableProvider>
    get() = sections.mapNotNull { it.provider }

val ProviderManagementState.settingsLists: ProviderSettingsLists
    get() = ProviderSettingsLists.of(providers)

/** An edit form: like a connect form, with the endpoint's current base URL filled in. */
fun providerEditForm(provider: ConnectableProvider): ProviderConnectForm {
    val base = ProviderConnectForm(provider)
    val url = provider.connectedBaseUrl ?: return base
    return if (base.fields.any { it.key == PROVIDER_FIELD_BASE_URL }) base.withValue(PROVIDER_FIELD_BASE_URL, url) else base
}
