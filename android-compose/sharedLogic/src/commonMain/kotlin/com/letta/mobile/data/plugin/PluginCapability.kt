package com.letta.mobile.data.plugin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** When a capability is consented to (plan section 3.3). */
enum class PluginConsentTime {
    /** On the host, at install and again when an update asks for it anew. */
    INSTALL,

    /** On the client device, the first time a page uses it; never without the OS permission. */
    FIRST_USE,
}

/**
 * What a plugin may do beyond running its own code (plan section 3.3): a closed set, so a manifest
 * naming anything else is refused at its pointer. [wire] is the manifest spelling.
 */
@Serializable
enum class PluginCapability(val wire: String, val consent: PluginConsentTime) {
    @SerialName("canvas:place") CANVAS_PLACE("canvas:place", PluginConsentTime.INSTALL),
    @SerialName("canvas:read") CANVAS_READ("canvas:read", PluginConsentTime.INSTALL),
    @SerialName("assets:write") ASSETS_WRITE("assets:write", PluginConsentTime.INSTALL),
    @SerialName("net:connect") NET_CONNECT("net:connect", PluginConsentTime.INSTALL),
    @SerialName("ui:pages") UI_PAGES("ui:pages", PluginConsentTime.INSTALL),
    @SerialName("ui:openLink") UI_OPEN_LINK("ui:openLink", PluginConsentTime.FIRST_USE),
    @SerialName("ui:clipboardWrite") UI_CLIPBOARD_WRITE("ui:clipboardWrite", PluginConsentTime.FIRST_USE),
    @SerialName("ui:camera") UI_CAMERA("ui:camera", PluginConsentTime.FIRST_USE),
    @SerialName("ui:microphone") UI_MICROPHONE("ui:microphone", PluginConsentTime.FIRST_USE),
    @SerialName("ui:geolocation") UI_GEOLOCATION("ui:geolocation", PluginConsentTime.FIRST_USE),
    ;

    companion object {
        val wireNames: List<String> = entries.map { it.wire }

        /** The capabilities a page may list in its `permissions`: the ones a client asks for on first use. */
        val pagePermissions: List<PluginCapability> = entries.filter { it.consent == PluginConsentTime.FIRST_USE }
    }
}

/**
 * What the owner agreed to for one version of a plugin (plan section 3.3): the capabilities and the
 * network origins, recorded on the host.
 */
@Serializable
data class PluginConsent(
    val capabilities: Set<PluginCapability>,
    val origins: Set<String>,
    val grantedAtEpochMs: Long,
    val forVersion: String,
) {
    /** Whether this consent covers everything [manifest] asks for. */
    fun covers(manifest: PluginManifest): Boolean = PluginConsentDiff.between(this, manifest).isEmpty

    companion object {
        /** Consent to exactly what [manifest] asks for, granted at [nowEpochMs]. */
        fun forManifest(manifest: PluginManifest, nowEpochMs: Long): PluginConsent = PluginConsent(
            capabilities = manifest.capabilities.toSet(),
            origins = manifest.net.connect.toSet(),
            grantedAtEpochMs = nowEpochMs,
            forVersion = manifest.version,
        )
    }
}

/**
 * What a manifest asks for that a consent does not cover: the capabilities and origins an update
 * adds, and whether its runtime kind changed (the isolation the consent dialog showed, R1). A
 * non-empty diff means the update must be consented to again; what an update drops never asks.
 */
data class PluginConsentDiff(
    val addedCapabilities: Set<PluginCapability>,
    val addedOrigins: Set<String>,
    val runtimeChanged: Boolean = false,
) {
    val isEmpty: Boolean get() = !runtimeChanged && addsNothing

    private val addsNothing: Boolean get() = addedCapabilities.isEmpty() && addedOrigins.isEmpty()

    companion object {
        /** What [manifest] asks for beyond [consent]. */
        fun between(consent: PluginConsent?, manifest: PluginManifest): PluginConsentDiff = PluginConsentDiff(
            addedCapabilities = manifest.capabilities.toSet() - consent?.capabilities.orEmpty(),
            addedOrigins = manifest.net.connect.toSet() - consent?.origins.orEmpty(),
        )

        /** What updating from [installed] to [update] adds that the owner has not agreed to. */
        fun forUpdate(installed: PluginManifest, consent: PluginConsent?, update: PluginManifest): PluginConsentDiff =
            between(consent, update).copy(runtimeChanged = PluginRuntime.kindOf(installed.runtime) != PluginRuntime.kindOf(update.runtime))
    }
}
