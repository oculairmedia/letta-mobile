package com.letta.mobile.ui.modelcontrol

import com.letta.mobile.data.repository.modelcontrol.ReasoningTier

/**
 * User-facing text of the picker, the Models sheet and the Providers settings
 * pages (letta-mobile-w4q4p.6.1), in one place. sharedUI publishes no
 * composeResources (its Android AAR carries none), so commonMain text lives in
 * Kotlin like the rest of this package; keeping it here makes the move to
 * `Res.string` a one-file change.
 */
object ModelControlStrings {
    // Picker (A)
    const val SEARCH_MODELS = "Search models"
    const val REFRESH_MODELS = "Refresh Models"
    const val REFRESHING_MODELS = "Refreshing…"
    const val EDIT_MODELS = "Edit Models…"
    const val NO_MODELS = "No models are shown yet"
    const val NO_MODELS_HINT = "Refresh, or turn some on in Edit Models."
    const val LOADING_MODELS = "Loading models…"
    const val CURRENT_MODEL = "Current model"
    const val EFFORT_DEFAULT = "Default"

    fun noMatch(query: String) = "No models match \"$query\""

    fun groupToggle(title: String, collapsed: Boolean) = if (collapsed) "Show $title models" else "Hide $title models"

    fun tierLabel(tier: ReasoningTier): String = when (tier) {
        ReasoningTier.NONE -> "None"
        ReasoningTier.MINIMAL -> "Min"
        ReasoningTier.LOW -> "Low"
        ReasoningTier.MEDIUM -> "Med"
        ReasoningTier.HIGH -> "High"
        ReasoningTier.XHIGH -> "XHigh"
        ReasoningTier.MAX -> "Max"
    }

    // Models sheet (B)
    const val MODELS_TITLE = "Models"
    const val CLOSE = "Close"
    const val ADD_PROVIDER = "Add provider…"
    const val NO_PROVIDER_MODELS = "No providers serve models yet. Add one to get started."

    fun shownInPicker(name: String, shown: Boolean) = if (shown) "$name, shown in the picker" else "$name, hidden from the picker"

    // Providers settings (C)
    const val PROVIDERS = "Providers"
    const val PAGE_ACCOUNTS = "Accounts"
    const val PAGE_API_KEYS = "API keys"
    const val PAGE_ENDPOINTS = "Custom Endpoints"
    const val PAGE_MODELS = "Models"
    const val REFRESH_PROVIDERS = "Refresh providers and models"

    const val CONNECT_ACCOUNT = "Connect an account"
    const val CONNECT_ACCOUNT_SUBTITLE = "Use a subscription you already pay for. The host keeps the sign-in; every app on it can use the models."
    const val HAVE_API_KEY = "Have an API key instead?"
    const val CONNECTED = "Connected"
    const val CONNECTED_BADGE = "Connected"
    const val OTHER_PROVIDERS = "Other providers"
    const val NO_ACCOUNTS_LEFT = "Every subscription account the host knows is connected."
    const val NOTHING_CONNECTED = "Nothing is connected yet."

    const val METHOD_TERMINAL = "Sign in once in your terminal, then come back to chat"
    const val METHOD_TERMINAL_CONNECTED = "Subscription account, signed in from the terminal"
    const val METHOD_API_KEY = "API key"
    const val METHOD_BROWSER_UNSUPPORTED =
        "Browser sign-in isn't available: this host accepts subscription sign-ins only from its own terminal."

    const val SIGN_IN_TITLE_PREFIX = "Connect "
    const val SIGN_IN_STEP_1 = "On the computer running the App Server, open a terminal and run:"
    const val SIGN_IN_COMMAND = "letta"
    const val SIGN_IN_STEP_2 = "Type /connect, choose the Local tab, then pick:"
    const val SIGN_IN_STEP_3 = "Finish the sign-in it opens, then come back here."
    const val CHECK_AGAIN = "Check again"

    const val API_KEYS_SUBTITLE = "The host checks each key with the provider before saving it. Saved keys are never sent back to the app."
    const val ADD_KEY = "Add key"
    const val REPLACE_KEY = "Replace key"
    const val SAVE_AND_VERIFY = "Save and verify"
    const val KEY_SAVED = "Key saved on the host"

    const val ENDPOINTS_SUBTITLE =
        "OpenAI-compatible servers and local runtimes. The host keeps one endpoint per provider; saving again replaces it."
    const val ADD_ENDPOINT = "Add endpoint"
    const val EDIT = "Edit"
    const val REMOVE = "Remove"
    const val SAVE = "Save"
    const val CANCEL = "Cancel"
    const val ENDPOINT_KEY_HINT = "Re-enter the key if the endpoint needs one; the host doesn't return saved keys."
    const val NAMED_ENDPOINTS_UNSUPPORTED = "Several endpoints of one kind aren't supported by this host yet."
    const val NOT_CONNECTED = "Not connected"

    fun connectTitle(name: String) = "Connect $name"

    fun removeLabel(name: String) = "Remove $name"

    fun showSecret(label: String, shown: Boolean) = if (shown) "Hide $label" else "Show $label"
}
