package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Host-side skill-root enumerator — the production adapter for
 * [NativeSkillsCatalog.hydrateFromHost] (letta-mobile-7dm1q, closing the
 * lgns8.21.2 gap).
 *
 * ## Why this exists
 *
 * `@letta-ai/letta-code` 0.29.12 advertises **no** skill enumeration on the wire:
 * there is no `skill_list` command, `skills_updated` is `{type,timestamp}`, and
 * `device_status.current_available_skills` is a hard-coded `[]` in both branches
 * of `buildDeviceStatus`. [NativeSkillsCatalog] correctly refuses to fabricate a
 * catalog from that, so before this adapter existed `skill.list` answered
 * `hydrated=false` forever and the Skills screen stayed empty.
 *
 * Skills are, however, plainly on disk: letta-code loads them from the skills
 * root (`LETTA_SKILLS_DIR`, default `~/.letta/skills`), one directory per skill,
 * each containing a `SKILL.md`. That directory IS the authoritative enumeration,
 * so this reads it directly.
 *
 * ## Format (verified against the live root, 2026-08-01)
 *
 * `SKILL.md` may open with YAML frontmatter:
 *
 * ```
 * ---
 * name: agent-messaging
 * description: Send a direct message to another Letta agent...
 * ---
 * ```
 *
 * Frontmatter is optional — several live skills have none and open straight on a
 * `# Heading`. Both shapes are supported: `name` falls back to the directory name
 * (which is what letta-code addresses the skill by), and `description` falls back
 * to the first prose paragraph after any heading.
 *
 * ## Safety
 *
 * READ-ONLY and fail-soft. A missing root, an unreadable file or a malformed
 * `SKILL.md` yields fewer entries — never an exception, and never a hydration
 * claim over nothing: [enumerate] returns null when the root does not exist, so
 * the caller leaves the catalog unhydrated rather than publishing an empty
 * catalog as authoritative.
 */
object HostSkillsEnumerator {

    /** `LETTA_SKILLS_DIR`, matching what lettashim/letta-code set. */
    const val SKILLS_DIR_ENV: String = "LETTA_SKILLS_DIR"

    /** Skill body/manifest file, per letta-code's skill format. */
    const val MANIFEST_FILE: String = "SKILL.md"

    /** Bound on how much of a SKILL.md is scanned for the description. */
    private const val DESCRIPTION_SCAN_LINES = 200

    /** Bound on a projected description so a page of skills cannot approach the frame cap. */
    private const val DESCRIPTION_MAX_CHARS = 500

    /**
     * Default skills root when neither the CLI option nor [SKILLS_DIR_ENV] is set.
     * `~/.letta/skills` is letta-code's own default.
     */
    fun defaultSkillsDir(): String =
        File(File(System.getProperty("user.home") ?: "", ".letta"), "skills").path

    /**
     * Resolve the configured skills root: explicit option wins, then the env var,
     * then [defaultSkillsDir].
     */
    fun resolveSkillsDir(
        explicit: String? = null,
        env: (String) -> String? = System::getenv,
    ): String = explicit?.takeIf { it.isNotBlank() }
        ?: env(SKILLS_DIR_ENV)?.takeIf { it.isNotBlank() }
        ?: defaultSkillsDir()

    /**
     * Enumerate the skills root.
     *
     * @return the catalog (possibly empty, if the root exists but holds no skills),
     *   or `null` when the root is absent/unreadable — which the caller MUST treat
     *   as "no authoritative enumeration" and leave the catalog unhydrated.
     */
    fun enumerate(skillsDir: String): JsonArray? = enumerate(File(skillsDir))

    fun enumerate(root: File): JsonArray? {
        if (!runCatching { root.isDirectory }.getOrDefault(false)) return null
        val entries = runCatching {
            root.listFiles()
                ?.filter { it.isDirectory }
                // Deterministic order: the same catalog every restart, so a client
                // diff after a reconnect is meaningful.
                ?.sortedBy { it.name }
                ?.mapNotNull { projectSkill(it) }
                .orEmpty()
        }.getOrElse { return null }
        return buildJsonArray { entries.forEach { add(it) } }
    }

    private fun projectSkill(dir: File): kotlinx.serialization.json.JsonObject? {
        val manifest = File(dir, MANIFEST_FILE)
        // A directory without a SKILL.md is not a skill (e.g. a stray `scripts/`
        // sibling); skipping it keeps the catalog honest.
        if (!runCatching { manifest.isFile }.getOrDefault(false)) return null
        val front = runCatching { parseManifest(manifest) }.getOrDefault(ManifestFields())
        return buildJsonObject {
            put("name", front.name?.takeIf { it.isNotBlank() } ?: dir.name)
            front.description?.takeIf { it.isNotBlank() }?.let { put("description", it.take(DESCRIPTION_MAX_CHARS)) }
            // `skill_path` is what native `skill_enable` accepts, so the client can
            // round-trip an entry from this catalog straight into skill.install.
            put("skill_path", dir.path)
            put("source", "host_enumeration")
        }
    }

    private class ManifestFields(val name: String? = null, val description: String? = null)

    /**
     * Minimal, dependency-free YAML-frontmatter read. Only scalar `name:` /
     * `description:` on the top level are needed; anything else is ignored rather
     * than pulling a YAML parser into sharedLogic for two fields.
     */
    private fun parseManifest(manifest: File): ManifestFields {
        val lines = manifest.useLines { seq -> seq.take(DESCRIPTION_SCAN_LINES).toList() }
        if (lines.firstOrNull()?.trim() == "---") {
            val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
            if (end >= 0) {
                val front = lines.subList(1, end + 1)
                return ManifestFields(
                    name = scalar(front, "name"),
                    description = scalar(front, "description")
                        ?: firstProse(lines.drop(end + 2)),
                )
            }
        }
        return ManifestFields(name = null, description = firstProse(lines))
    }

    private fun scalar(front: List<String>, key: String): String? {
        val prefix = "$key:"
        val line = front.firstOrNull { it.startsWith(prefix) } ?: return null
        return line.removePrefix(prefix).trim().trim('"', '\'').takeIf { it.isNotBlank() }
    }

    /** First non-blank, non-heading, non-fence line — the skill's opening prose. */
    private fun firstProse(lines: List<String>): String? = lines
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("```") && it != "---" }
}
