package com.letta.mobile.plugin.testkit

import com.letta.mobile.plugin.api.PluginEmit
import kotlinx.serialization.json.Json
import java.util.concurrent.CopyOnWriteArrayList

/** Everything a plugin said during a run, each with where it said it. */
internal class PluginOutputs {
    private val outputs = CopyOnWriteArrayList<Pair<String, String>>()

    fun add(where: String, text: String) {
        outputs += where to text
    }

    /** These outputs and what the plugin sent through [host]: log lines, emits and request URLs. */
    fun with(host: FakePluginHost): List<Pair<String, String>> =
        outputs.toList() +
            host.logs.map { "a log line" to "${it.message} ${it.fields}" } +
            host.emits.map { "an emit" to Json.encodeToString(PluginEmit.serializer(), it) } +
            host.httpRequests.map { "a request URL" to it.url }
}

/**
 * The secret rule (plan section 3.4): no secret value in anything the plugin says. Headers of HTTP
 * requests are where secrets belong and are not scanned; URLs are.
 */
internal object SecretScan {
    /** Secrets shorter than this are not scanned (they would match by accident). */
    private const val MIN_SCANNED_LENGTH = 6

    fun leaks(secrets: Map<String, String>, outputs: List<Pair<String, String>>): List<ConformanceFinding> =
        secrets.filterValues { it.length >= MIN_SCANNED_LENGTH }
            .flatMap { (name, value) -> outputs.filter { (_, text) -> value in text }.map { (where, _) -> name to where } }
            .distinct()
            .map { (name, where) -> ConformanceFinding(ConformanceRule.SECRET_LEAK, "the secret '$name' appears in $where") }
}
