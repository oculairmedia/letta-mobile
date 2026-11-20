package com.letta.mobile.ui.modelcontrol

import com.letta.mobile.data.servicecredentials.ServiceCredentialErrorCode

/** The Services page's words. Service names, summaries and field labels come from the host. */
internal object ServiceCredentialStrings {
    const val SUBTITLE = "Credentials for the other services this host calls. They are kept on the host; " +
        "this device can set or replace a key but never read it back."
    const val UNSUPPORTED = "This host does not keep service credentials. Update the host to manage them here."
    const val NONE = "This host offers no services yet."
    const val READ_ONLY = "Only a device with admin rights on the host can change these."
    const val KEY_SAVED = "Key saved"
    const val NOT_SET_UP = "Not set up"
    const val NO_KEY = "No key"
    const val NOT_TESTED = "Not tested"
    const val TEST_PASSED = "Test passed"
    const val TEST = "Test"
    const val TESTING = "Testing…"
    const val SET_UP = "Set up"
    const val EDIT = "Edit"
    const val SAVE = "Save"
    const val CANCEL = "Cancel"
    const val REMOVE = "Remove"
    const val ADDRESS = "Address"
    const val SECRET_KEPT_HINT = "Leave empty to keep the saved key."
    const val TEST_NOTE = "Test sends one fixed made-up sentence, never your conversations."

    fun replaceSecret(label: String) = "Replace $label"

    fun removeTitle(name: String) = "Remove $name?"

    const val REMOVE_MESSAGE = "The host deletes its key and settings for this service. " +
        "Features that use it stop working until it is set up again."

    fun showSecret(label: String, shown: Boolean) = if (shown) "Hide $label" else "Show $label"

    /** A failure or failed test in words; never the service's own response. */
    fun error(code: ServiceCredentialErrorCode): String = when (code) {
        ServiceCredentialErrorCode.INVALID_REQUEST -> "The host refused these settings."
        ServiceCredentialErrorCode.UNKNOWN_SERVICE -> "The host no longer offers this service."
        ServiceCredentialErrorCode.NOT_CONFIGURED -> "Save a key before testing."
        ServiceCredentialErrorCode.INSECURE_TRANSPORT -> "A key can only be sent over https."
        ServiceCredentialErrorCode.REVISION_CONFLICT -> "Someone changed this service meanwhile. Try again."
        ServiceCredentialErrorCode.UNAUTHORIZED -> "The service refused the key."
        ServiceCredentialErrorCode.RATE_LIMITED -> "The service is rate limiting this account."
        ServiceCredentialErrorCode.UNREACHABLE -> "The host could not reach the service."
        ServiceCredentialErrorCode.UPSTREAM_TIMEOUT -> "The service did not answer in time."
        ServiceCredentialErrorCode.UPSTREAM_ERROR -> "The service failed to answer."
        ServiceCredentialErrorCode.NOT_AUTHORIZED -> READ_ONLY
        ServiceCredentialErrorCode.UNSUPPORTED -> UNSUPPORTED
        ServiceCredentialErrorCode.INTERNAL -> "The host could not complete that."
    }
}
