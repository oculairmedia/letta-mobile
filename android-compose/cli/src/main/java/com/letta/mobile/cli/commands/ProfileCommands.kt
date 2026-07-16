package com.letta.mobile.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.letta.mobile.cli.runtime.CliJson
import com.letta.mobile.cli.runtime.CliProfile
import com.letta.mobile.cli.runtime.CliProfileDocument
import com.letta.mobile.cli.runtime.CliProfilePrefs
import com.letta.mobile.cli.runtime.CliProfileStore
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class ProfileCommand : CliktCommand(
    name = "profile",
) {
    override fun run() = Unit
}

internal class ProfileListCommand : CliktCommand(name = "list") {
    private val showToken by option("--show-token").flag(default = false)

    override fun run() {
        val document = CliProfileStore.default().load()
        println(profileJson(document.redacted(unless = showToken)))
    }
}

internal class ProfileShowCommand : CliktCommand(name = "show") {
    private val name by argument("name").optional()
    private val showToken by option("--show-token").flag(default = false)

    override fun run() {
        val store = CliProfileStore.default()
        val document = store.load()
        val profileName = name ?: document.activeProfile ?: throw UsageError("No active profile")
        val profile = document.profiles.find { it.name == profileName }
            ?: throw UsageError("profile not found: $profileName")
        println(profileJson(profile.redacted(unless = showToken)))
    }
}

internal class ProfileSetCommand : CliktCommand(name = "set") {
    private val name by argument("name")
    private val baseUrl by option("--base-url", envvar = "LETTA_BASE_URL")
    private val token by option("--token", envvar = "LETTA_TOKEN")
    private val defaultAgentId by option("--agent", "--default-agent", envvar = "LETTA_AGENT_ID")
    private val defaultConversationId by option("--conversation", "--default-conversation", envvar = "LETTA_CONVERSATION_ID")
    private val defaultProjectId by option("--project", "--default-project")
    private val theme by option("--theme")
    private val themePreset by option("--theme-preset")
    private val dynamicColor by option("--dynamic-color")
    private val enableProjects by option("--enable-projects")
    private val chatBackground by option("--chat-background")
    private val chatFontScale by option("--chat-font-scale")
    private val resumeRecentConversation by option("--resume-recent-conversation")
    private val clearToken by option("--clear-token").flag(default = false)
    private val active by option("--active").flag(default = false)
    private val showToken by option("--show-token").flag(default = false)

    override fun run() {
        val store = CliProfileStore.default()
        val existing = store.load().profiles.find { it.name == name }
        val next = CliProfile(
            name = name,
            baseUrl = baseUrl ?: existing?.baseUrl,
            token = when {
                clearToken -> null
                token != null -> token
                else -> existing?.token
            },
            defaultAgentId = defaultAgentId ?: existing?.defaultAgentId,
            defaultConversationId = defaultConversationId ?: existing?.defaultConversationId,
            defaultProjectId = defaultProjectId ?: existing?.defaultProjectId,
            prefs = CliProfilePrefs(
                theme = theme ?: existing?.prefs?.theme,
                themePreset = themePreset ?: existing?.prefs?.themePreset,
                dynamicColor = dynamicColor.parseBoolOption("--dynamic-color") ?: existing?.prefs?.dynamicColor,
                enableProjects = enableProjects.parseBoolOption("--enable-projects") ?: existing?.prefs?.enableProjects,
                chatBackground = chatBackground ?: existing?.prefs?.chatBackground,
                chatFontScale = chatFontScale.parseFloatOption("--chat-font-scale") ?: existing?.prefs?.chatFontScale,
                resumeRecentConversation = resumeRecentConversation.parseBoolOption("--resume-recent-conversation")
                    ?: existing?.prefs?.resumeRecentConversation,
            )
        )
        store.upsert(next, makeActive = active)
        println(profileJson(next.redacted(unless = showToken)))
    }
}

internal class ProfileUseCommand : CliktCommand(name = "use") {
    private val name by argument("name")

    override fun run() {
        CliProfileStore.default().setActive(name)
        println(jsonStatus("activeProfile", name))
    }
}

internal class ProfileDeleteCommand : CliktCommand(name = "delete") {
    private val name by argument("name")

    override fun run() {
        val document = CliProfileStore.default().delete(name)
        println(profileJson(document.redacted(unless = false)))
    }
}

internal class ProfileExportCommand : CliktCommand(name = "export") {
    private val out by option("--out")
    private val redactToken by option("--redact-token").flag(default = false)

    override fun run() {
        val document = CliProfileStore.default().load().let { if (redactToken) it.redacted(unless = false) else it }
        val json = profileJson(document)
        if (out == null) {
            println(json)
        } else {
            val path = Path.of(out)
            path.parent?.let { Files.createDirectories(it) }
            Files.write(path, json.toByteArray(Charsets.UTF_8))
            println(jsonStatus("exported", path.toString()))
        }
    }
}

internal class ProfileImportCommand : CliktCommand(name = "import") {
    private val file by option("--file").required()

    override fun run() {
        val document = CliJson.decodeFromString<CliProfileDocument>(
            String(Files.readAllBytes(Path.of(file)), Charsets.UTF_8)
        )
        val normalized = CliProfileStore.default().replace(document)
        println(profileJson(normalized.redacted(unless = false)))
    }
}

private fun String?.parseBoolOption(optionName: String): Boolean? {
    if (this == null) return null
    return when (trim().lowercase()) {
        "true", "1", "yes", "y", "on" -> true
        "false", "0", "no", "n", "off" -> false
        else -> throw UsageError("$optionName must be true or false")
    }
}

private fun String?.parseFloatOption(optionName: String): Float? {
    if (this == null) return null
    return toFloatOrNull() ?: throw UsageError("$optionName must be a number")
}

private fun CliProfileDocument.redacted(unless: Boolean): CliProfileDocument =
    if (unless) this else copy(profiles = profiles.map { it.redacted(unless = false) })

private fun CliProfile.redacted(unless: Boolean): CliProfile =
    if (unless) this else copy(token = token?.let { "<redacted>" })

private fun profileJson(document: CliProfileDocument): String =
    outputJson.encodeToString(CliProfileDocument.serializer(), document)

private fun profileJson(profile: CliProfile): String =
    outputJson.encodeToString(CliProfile.serializer(), profile)

private val outputJson = Json {
    prettyPrint = true
    explicitNulls = false
    encodeDefaults = true
}
