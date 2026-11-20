package com.letta.mobile.data.voice.core

import kotlin.jvm.JvmInline

/**
 * An agent's voice core: one short description of how the agent sounds (for example "a calm, low
 * voice with a slight smile"), kept in the agent's memory as its `voice` block ([BLOCK_LABEL]) and
 * edited by the agent or the person through a typed setting. The speech server renders it once into
 * a cached reference clip (`voice_core`); the reply's mood modules are bounded offsets on it.
 *
 * It is only ever read from that block, never from a reply or a client's request, so nothing an agent
 * says aloud can change the voice. Its [toString] carries no text.
 */
@JvmInline
value class VoiceCore private constructor(
    /** The sanitised description, at most [MAX_LENGTH] characters, never logged. */
    val description: String,
) {
    override fun toString(): String = "VoiceCore(length=${description.length})"

    /** Reading and writing the block. */
    companion object {
        /** The label of the memory block that holds an agent's voice core. */
        const val BLOCK_LABEL: String = "voice"

        /** The longest description, in characters. */
        const val MAX_LENGTH: Int = 200

        /**
         * The voice core a `voice` block's [value] holds, or null when it holds none (empty, or only
         * front matter). A leading front-matter section (between two `---` delimiter lines) is skipped, control characters
         * and line breaks become spaces, runs of spaces collapse, and the result is cut to
         * [MAX_LENGTH] characters at the last word that fits.
         */
        fun fromBlock(value: String?): VoiceCore? {
            val body = withoutFrontMatter(value ?: return null)
            val clean = sanitise(body)
            return clean.takeIf { it.isNotEmpty() }?.let(::VoiceCore)
        }

        /**
         * What a setting writes into the block for [description]: the sanitised text, or the empty
         * string to clear it, after the front matter of the block as it stands ([existing]), which is
         * kept as it was. Null when [description] is longer than [MAX_LENGTH] once sanitised, so the
         * setting can say so instead of cutting the person's words.
         */
        fun blockValueOf(description: String, existing: String? = null): String? {
            val clean = sanitise(description, cut = false).takeIf { it.length <= MAX_LENGTH } ?: return null
            return frontMatterOf(existing.orEmpty()) + clean
        }

        /** [description] as the block stores it, cut to [MAX_LENGTH] unless [cut] is false. */
        private fun sanitise(description: String, cut: Boolean = true): String {
            val flat = buildString {
                description.forEach { char -> append(if (char.isISOControl() || char.isWhitespace()) ' ' else char) }
            }
            val collapsed = flat.split(' ').filter { it.isNotEmpty() }.joinToString(" ")
            if (!cut || collapsed.length <= MAX_LENGTH) return collapsed
            val window = collapsed.substring(0, MAX_LENGTH + 1)
            val lastSpace = window.lastIndexOf(' ')
            return (if (lastSpace > 0) window.substring(0, lastSpace) else collapsed.substring(0, MAX_LENGTH)).trimEnd()
        }

        /** [value] without its front matter; [value] itself when it has none. */
        private fun withoutFrontMatter(value: String): String = splitFrontMatter(value)?.second ?: value

        /** The front-matter section [value] starts with, through its closing line's break; empty when none. */
        private fun frontMatterOf(value: String): String = splitFrontMatter(value)?.first.orEmpty()

        /**
         * [value] split into its front matter and the rest, or null when it has none. Front matter is a
         * section between two delimiter lines, each exactly `---` (trailing spaces allowed), the first
         * opening the value. Text that merely starts with `---` (`--- A quiet voice.`), or an opening line
         * that is never closed, is no front matter: it is all description.
         */
        private fun splitFrontMatter(value: String): Pair<String, String>? {
            val lines = value.trimStart().split('\n')
            if (!isDelimiter(lines.first())) return null
            val close = (1 until lines.size).firstOrNull { isDelimiter(lines[it]) } ?: return null
            val frontMatter = lines.subList(0, close + 1).joinToString("\n") + "\n"
            return frontMatter to lines.subList(close + 1, lines.size).joinToString("\n")
        }

        private fun isDelimiter(line: String): Boolean = line.trimEnd() == FRONT_MATTER

        private const val FRONT_MATTER = "---"
    }
}
