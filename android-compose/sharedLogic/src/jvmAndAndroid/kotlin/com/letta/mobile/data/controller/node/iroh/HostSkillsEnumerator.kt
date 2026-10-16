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
 * Skills are, however, plainly on disk: letta-code loads its global skills from
 * `<LETTA_HOME>/skills` (default `~/.letta/skills`), one directory per skill, each
 * containing a `SKILL.md`. That directory IS the authoritative enumeration of the
 * global source, so this reads it directly.
 *
 * ## Controllability (letta-mobile skills-honest-controls)
 *
 * letta-code's `skill_disable` only unlinks a SYMLINK at `<LETTA_HOME>/skills/<name>`
 * and refuses a real directory; `skill_enable` creates such a symlink. So an entry is
 * `controllable` iff it is a symlink directly inside that control root — the same
 * lstat rule letta-code applies. A real folder (or any entry when the enumerated root
 * is not the control root) is listed read-only, never as a toggle that cannot work.
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
 * `# Heading`. Both shapes are supported: `name` is always the directory name
 * (what `skill_disable` addresses), and `description` falls back to the first
 * prose paragraph after any heading.
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

    /** letta-code's home override; `skill_enable` / `skill_disable` act on `<LETTA_HOME>/skills`. */
    const val LETTA_HOME_ENV: String = "LETTA_HOME"

    /** Wire `source` of a host-enumerated skill: letta-code's global skills source. */
    const val GLOBAL_SOURCE: String = "global"

    /**
     * letta-code's skill control root, resolved as its skill handler does:
     * `LETTA_HOME` when set, else `~/.letta`, plus `skills`. Also the default
     * enumeration root when neither the CLI option nor [SKILLS_DIR_ENV] is set.
     */
    fun defaultSkillsDir(env: (String) -> String? = System::getenv): String {
        val home = env(LETTA_HOME_ENV)?.takeIf { it.isNotBlank() }
            ?: File(System.getProperty("user.home") ?: "", ".letta").path
        return File(home, "skills").path
    }

    /**
     * Resolve the configured skills root: explicit option wins, then the env var,
     * then [defaultSkillsDir].
     */
    fun resolveSkillsDir(
        explicit: String? = null,
        env: (String) -> String? = System::getenv,
    ): String = explicit?.takeIf { it.isNotBlank() }
        ?: env(SKILLS_DIR_ENV)?.takeIf { it.isNotBlank() }
        ?: defaultSkillsDir(env)

    /**
     * Enumerate the skills root.
     *
     * @return the catalog (possibly empty, if the root exists but holds no skills),
     *   or `null` when the root is absent/unreadable — which the caller MUST treat
     *   as "no authoritative enumeration" and leave the catalog unhydrated.
     */
    fun enumerate(skillsDir: String, controlRoot: String = defaultSkillsDir()): JsonArray? =
        enumerate(File(skillsDir), File(controlRoot))

    /**
     * @param controlRoot letta-code's `<LETTA_HOME>/skills`; entries are controllable only when
     *   [root] IS that directory (compared without resolving links, as letta-code joins paths)
     *   and the entry itself is a symlink.
     */
    fun enumerate(root: File, controlRoot: File = File(defaultSkillsDir())): JsonArray? {
        if (!runCatching { root.isDirectory }.getOrDefault(false)) return null
        val isControlRoot = root.absoluteFile.normalize() == controlRoot.absoluteFile.normalize()
        val entries = runCatching {
            root.listFiles()
                ?.filter { it.isDirectory }
                // Deterministic order: the same catalog every restart, so a client
                // diff after a reconnect is meaningful.
                ?.sortedBy { it.name }
                ?.mapNotNull { projectSkill(it, controls = isControlRoot) }
                .orEmpty()
        }.getOrElse { return null }
        return buildJsonArray { entries.forEach { add(it) } }
    }

    private fun projectSkill(dir: File, controls: Boolean): kotlinx.serialization.json.JsonObject? {
        // A directory without a SKILL.md is not a skill (e.g. a stray `scripts/`
        // sibling); skipping it keeps the catalog honest. letta-code matches the
        // manifest name case-insensitively, so `skill.md` counts too.
        val manifest = manifestOf(dir) ?: return null
        val description = runCatching { parseManifest(manifest) }.getOrNull()
        return buildJsonObject {
            // The directory (link) name, not the frontmatter name: it is what
            // `skill_disable {name}` addresses under the control root.
            put("name", dir.name)
            description?.takeIf { it.isNotBlank() }?.let { put("description", it.take(DESCRIPTION_MAX_CHARS)) }
            put("skill_path", dir.path)
            put("source", GLOBAL_SOURCE)
            put("controllable", controls && runCatching { java.nio.file.Files.isSymbolicLink(dir.toPath()) }.getOrDefault(false))
        }
    }

    private fun manifestOf(dir: File): File? = runCatching {
        File(dir, MANIFEST_FILE).takeIf { it.isFile }
            ?: dir.listFiles()?.firstOrNull { it.isFile && it.name.equals(MANIFEST_FILE, ignoreCase = true) }
    }.getOrNull()

    /**
     * Minimal, dependency-free YAML-frontmatter read. Only a top-level scalar
     * `description:` is needed; anything else is ignored rather than pulling a YAML
     * parser into sharedLogic for one field.
     */
    private fun parseManifest(manifest: File): String? {
        val lines = manifest.useLines { seq -> seq.take(DESCRIPTION_SCAN_LINES).toList() }
        if (lines.firstOrNull()?.trim() == "---") {
            val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
            if (end >= 0) {
                val front = lines.subList(1, end + 1)
                return scalar(front, "description") ?: firstProse(lines.drop(end + 2))
            }
        }
        return firstProse(lines)
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
