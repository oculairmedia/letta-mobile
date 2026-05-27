package com.letta.mobile.cli.runtime

import com.github.ajalt.clikt.core.UsageError
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
internal data class CliProfileDocument(
    val activeProfile: String? = null,
    val profiles: List<CliProfile> = emptyList(),
)

@Serializable
internal data class CliProfile(
    val name: String,
    val baseUrl: String? = null,
    val token: String? = null,
    val defaultAgentId: String? = null,
    val defaultConversationId: String? = null,
    val defaultProjectId: String? = null,
    val prefs: CliProfilePrefs = CliProfilePrefs(),
)

@Serializable
internal data class CliProfilePrefs(
    val theme: String? = null,
    val themePreset: String? = null,
    val dynamicColor: Boolean? = null,
    val enableProjects: Boolean? = null,
    val chatBackground: String? = null,
    val chatFontScale: Float? = null,
    val resumeRecentConversation: Boolean? = null,
)

internal data class CliConnection(
    val profileName: String?,
    val baseUrl: String,
    val token: String?,
    val profile: CliProfile?,
)

internal class CliProfileStore(
    private val path: Path = defaultPath(),
) {
    fun load(): CliProfileDocument {
        if (!Files.exists(path)) return CliProfileDocument()
        return runCatching {
            CliJson.decodeFromString<CliProfileDocument>(
                String(Files.readAllBytes(path), Charsets.UTF_8)
            )
        }.getOrElse { CliProfileDocument() }
    }

    fun save(document: CliProfileDocument) {
        path.parent?.let { Files.createDirectories(it) }
        val tmp = path.resolveSibling("${path.fileName}.tmp")
        Files.write(tmp, CliJson.encodeToString(document).toByteArray(Charsets.UTF_8))
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun upsert(profile: CliProfile, makeActive: Boolean): CliProfileDocument {
        val document = load()
        val existing = document.profiles.filterNot { it.name == profile.name }
        val active = if (makeActive || document.activeProfile == null) profile.name else document.activeProfile
        return document.copy(
            activeProfile = active,
            profiles = (existing + profile).sortedBy { it.name },
        ).also(::save)
    }

    fun setActive(name: String): CliProfileDocument {
        val document = load()
        require(document.profiles.any { it.name == name }) { "profile not found: $name" }
        return document.copy(activeProfile = name).also(::save)
    }

    fun delete(name: String): CliProfileDocument {
        val document = load()
        val profiles = document.profiles.filterNot { it.name == name }
        val active = when {
            document.activeProfile != name -> document.activeProfile
            else -> profiles.firstOrNull()?.name
        }
        return document.copy(activeProfile = active, profiles = profiles).also(::save)
    }

    fun replace(document: CliProfileDocument): CliProfileDocument {
        val normalizedProfiles = document.profiles.distinctBy { it.name }.sortedBy { it.name }
        val normalizedActive = document.activeProfile
            ?.takeIf { active -> normalizedProfiles.any { it.name == active } }
            ?: normalizedProfiles.firstOrNull()?.name
        return document.copy(
            activeProfile = normalizedActive,
            profiles = normalizedProfiles,
        ).also(::save)
    }

    fun resolve(
        profileName: String?,
        explicitBaseUrl: String?,
        explicitToken: String?,
    ): CliConnection {
        val document = load()
        val resolvedProfileName = profileName ?: document.activeProfile
        val profile = resolvedProfileName?.let { name -> document.profiles.find { it.name == name } }
        if (profileName != null && profile == null) {
            throw UsageError("profile not found: $profileName")
        }
        return CliConnection(
            profileName = resolvedProfileName,
            baseUrl = explicitBaseUrl ?: profile?.baseUrl ?: DEFAULT_BASE_URL,
            token = explicitToken ?: profile?.token,
            profile = profile,
        )
    }

    fun exportJson(): String = prettyJson.encodeToString(CliProfileDocument.serializer(), load())

    companion object {
        const val DEFAULT_BASE_URL = "https://letta.oculair.ca"

        fun defaultPath(): Path {
            val home = System.getenv("LETTA_MOBILE_CLI_HOME")?.takeIf { it.isNotBlank() }
                ?: Path.of(System.getProperty("user.home"), ".letta-mobile-cli").toString()
            return Path.of(home, "profiles.json")
        }

        fun default(): CliProfileStore = CliProfileStore(defaultPath())
    }
}

private val prettyJson = kotlinx.serialization.json.Json {
    prettyPrint = true
    explicitNulls = false
    encodeDefaults = true
}
