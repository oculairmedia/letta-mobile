package com.letta.mobile.data.plugin

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * One secret's value (plan section 3.4). It never prints: [toString] is redacted, so a secret held
 * in a data class, a log line or an exception message shows only that it is there. [reveal] is
 * for the one place that hands it to the plugin (env, headers, `PluginHost.secret`).
 */
class SecretValue(private val value: String) {
    fun reveal(): String = value

    override fun toString(): String = REDACTED

    override fun equals(other: Any?): Boolean = other is SecretValue && other.value == value

    override fun hashCode(): Int = value.hashCode()

    companion object {
        const val REDACTED: String = "<redacted>"
    }
}

/** Whether a declared secret has a value on the host; the only thing the management API ever answers. */
enum class SecretState { SET, UNSET }

/**
 * A plugin's secret values by name, on the host only. Write-only from the outside: [status] says
 * which are set, [scrubber] guards the plugin's output, and nothing here prints a value.
 */
class PluginSecrets(private val values: Map<String, SecretValue> = emptyMap()) {
    operator fun get(name: String): SecretValue? = values[name]

    fun with(name: String, value: String): PluginSecrets = PluginSecrets(values + (name to SecretValue(value)))

    fun without(name: String): PluginSecrets = PluginSecrets(values - name)

    /** SET or UNSET for every secret [manifest] declares (values for undeclared names are not reported). */
    fun status(manifest: PluginManifest): Map<String, SecretState> =
        manifest.secrets.associate { it.name to if (values[it.name]?.reveal().isNullOrEmpty()) SecretState.UNSET else SecretState.SET }

    fun scrubber(): SecretScrubber = SecretScrubber(values.values.map(SecretValue::reveal))

    override fun toString(): String = "PluginSecrets(${values.keys.joinToString()})"
}

/**
 * Holds every plugin output (action results, logs, emits, stderr) to the plugin's secrets (plan
 * section 3.4), failing closed: a text that holds any secret, or any secret's base64 (standard or
 * URL-safe, padded or not), is replaced whole by [REFUSED], never partially masked, so a leak is
 * visible and nothing of it travels on.
 */
class SecretScrubber(secrets: Collection<String>) {
    private val forms: List<String> = secrets.filter { it.isNotEmpty() }.flatMap(::formsOf).distinct()

    /** Whether [text] holds a secret in any of its forms. */
    fun leaks(text: String): Boolean = forms.any { text.contains(it) }

    /** [text] unchanged, or [REFUSED] when it [leaks]. */
    fun scrub(text: String): String = if (leaks(text)) REFUSED else text

    /** [fields] with every leaking value replaced by [REFUSED] (a log line's structured fields). */
    fun scrub(fields: Map<String, String>): Map<String, String> = fields.mapValues { (_, value) -> scrub(value) }

    override fun toString(): String = "SecretScrubber(${forms.size} forms)"

    companion object {
        const val REFUSED: String = "[refused: the plugin's output contained a secret]"

        @OptIn(ExperimentalEncodingApi::class)
        private fun formsOf(secret: String): List<String> {
            val bytes = secret.encodeToByteArray()
            val standard = Base64.encode(bytes)
            val urlSafe = Base64.UrlSafe.encode(bytes)
            return listOf(secret, standard, standard.trimEnd('='), urlSafe, urlSafe.trimEnd('='))
        }
    }
}
