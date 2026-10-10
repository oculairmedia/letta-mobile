package com.letta.mobile.data.meridian

import com.letta.mobile.data.meridian.endpoint.MeridianShellCallSignal
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Which shell tool calls are `meridian` CLI calls safe to approve without asking
 * (letta-mobile-jna0o.7). An external tool never raised an approval card; its CLI form must not
 * either, in any permission mode, or moving the tools behind the CLI would put a card on every
 * canvas edit under Standard and Strict.
 *
 * Deliberately narrower than letta-code's `Bash(meridian:*)` allow rule, which is a raw string
 * prefix of the whole command line and so also allows `meridian canvas list; rm -rf ~`. Allowed
 * here is exactly ONE simple command:
 * - the program word is `meridian` (no path, no env prefix);
 * - its words are plain, single-quoted, or double-quoted without `$`, backquote or backslash;
 *   no `;`, `&`, `|`, `<`, `>`, `(`, `)`, `$`, backquote or newline anywhere outside a heredoc body;
 * - optionally ONE heredoc with a QUOTED delimiter (`<<'JSON'`, `<<"JSON"`, `<<-'JSON'`), so the
 *   shell expands nothing in the body, closed by its delimiter line with nothing after it;
 * - the command is one of the router's groups: `canvas`, `plugin`, `tool`, `guide`, `schema`,
 *   `help`, `agents find`, `agent-message send`, or help flags. Nothing that the developer CLI
 *   also answers (`rest`, `profile`, `peer`, ...) is allowed.
 *
 * The same rule ships to letta-code as a PermissionRequest hook
 * (scripts/deploy/meridian-permission-hook.cjs); both are checked against
 * scripts/deploy/meridian-allowlist-vectors.json.
 */
object MeridianShellAllowList {
    const val PROGRAM = "meridian"

    /** First word -> allowed second words (null: any). */
    private val GROUPS: Map<String, Set<String>?> = mapOf(
        MeridianCommandCatalog.CANVAS to null,
        MeridianCommandCatalog.PLUGIN to null,
        MeridianCommandCatalog.TOOL to null,
        MeridianCommandCatalog.GUIDE to null,
        MeridianCommandCatalog.SCHEMA to null,
        MeridianCommandCatalog.HELP to null,
        "--help" to null,
        "-h" to null,
        MeridianCommandCatalog.AGENTS to setOf("find"),
        MeridianCommandCatalog.AGENT_MESSAGE to setOf("send"),
    )

    private val SAFE_CHAR = Regex("""[A-Za-z0-9_@%+=:,./-]""")
    private val HEREDOC = Regex("""^<<(-?)[ \t]*(?:'([A-Za-z_][A-Za-z0-9_]*)'|"([A-Za-z_][A-Za-z0-9_]*)")[ \t]*$""")
    private val json = Json { ignoreUnknownKeys = true }

    /** True when the shell tool call [toolName] with [argumentsJson] is an allowed `meridian` call. */
    fun allows(toolName: String?, argumentsJson: String?): Boolean {
        if (toolName !in MeridianShellCallSignal.SHELL_TOOLS || argumentsJson.isNullOrBlank()) return false
        val command = MeridianShellCallSignal.shellCommand(parse(argumentsJson)) ?: return false
        return allowsCommand(command)
    }

    fun allowsCommand(command: String): Boolean {
        val lines = command.replace("\r\n", "\n").trim().split('\n')
        val head = lines.first()
        val marker = head.indexOf("<<")
        val invocation = if (marker < 0) head else head.substring(0, marker)
        val bodyOk = if (marker < 0) lines.size == 1 else heredocClosed(head.substring(marker), lines.drop(1))
        val words = words(invocation) ?: return false
        return bodyOk && words.firstOrNull() == PROGRAM && pathAllowed(words.drop(1))
    }

    private fun heredocClosed(redirect: String, body: List<String>): Boolean {
        val match = HEREDOC.matchEntire(redirect.trimEnd()) ?: return false
        val stripTabs = match.groupValues[1] == "-"
        val tag = match.groupValues[2].ifEmpty { match.groupValues[3] }
        val end = body.indexOfFirst { (if (stripTabs) it.trimStart('\t') else it) == tag }
        return end >= 0 && body.drop(end + 1).all { it.isBlank() }
    }

    private fun pathAllowed(args: List<String>): Boolean {
        val first = args.firstOrNull() ?: return true
        if (first !in GROUPS) return false
        val verbs = GROUPS[first] ?: return true
        return args.getOrNull(1) in verbs
    }

    /** The shell words of [text], or null when it holds anything but plain and safely quoted words. */
    private fun words(text: String): List<String>? {
        val words = mutableListOf<String>()
        val current = StringBuilder()
        var inWord = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == ' ' || c == '\t' -> {
                    if (inWord) words += current.toString().also { current.clear() }
                    inWord = false
                    i += 1
                }
                c == '\'' || c == '"' -> {
                    val close = text.indexOf(c, i + 1).takeIf { it > i } ?: return null
                    val quoted = text.substring(i + 1, close)
                    if (c == '"' && quoted.any { it in DOUBLE_QUOTE_UNSAFE }) return null
                    current.append(quoted)
                    inWord = true
                    i = close + 1
                }
                SAFE_CHAR.matches(c.toString()) -> {
                    current.append(c)
                    inWord = true
                    i += 1
                }
                else -> return null
            }
        }
        if (inWord) words += current.toString()
        return words
    }

    private fun parse(text: String): JsonElement? = try {
        json.parseToJsonElement(text)
    } catch (_: SerializationException) {
        null
    }

    private const val DOUBLE_QUOTE_UNSAFE = "$`\\\n"
}
