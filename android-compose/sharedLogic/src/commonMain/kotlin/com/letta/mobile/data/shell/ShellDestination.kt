package com.letta.mobile.data.shell

import com.letta.mobile.data.lens.LensDestination

/**
 * letta-mobile-ckmub.3.4 (hard cut S5, primitive P8): the one destination model of the shell.
 *
 * Every client has a route model of its own (Android's typed navigation routes, the desktop's
 * `DesktopDestination` plus its selection, the web's canonical [ShellRoute] path) and every one of
 * them maps to and from this. The page for a destination is drawn by the shared `ShellPage` switch
 * in sharedUI; platform navigation hosts only translate their routes, deep links and back stack.
 *
 * Ids are plain strings so that every route model (serialized navigation arguments, URL query
 * values, saved state) can carry them without adapters.
 */
sealed interface ShellDestination {
    /** Which page draws this destination: the key of the shared page registry. */
    val kind: ShellPageKind

    /** The agent this destination is about, when it is about one. */
    val agentId: String? get() = null

    /** The conversation this destination is about, when it is about one. */
    val conversationId: String? get() = null

    /** The fleet dashboard: recent conversations across agents, the chatbox and pinned items. */
    data object Home : ShellDestination {
        override val kind get() = ShellPageKind.Home
    }

    /**
     * A conversation. With a [conversationId] that conversation; with only an [agentId] that agent's
     * most recent one (or a first one); with neither, the chat page's own default (the conversation
     * list on Android, the last selection on desktop and web).
     */
    data class Chat(
        override val agentId: String? = null,
        override val conversationId: String? = null,
    ) : ShellDestination {
        override val kind get() = ShellPageKind.Chat
    }

    /** A canvas board: a library board by [canvasId], or a conversation's own board. */
    data class Canvas(
        val canvasId: String? = null,
        override val conversationId: String? = null,
        override val agentId: String? = null,
    ) : ShellDestination {
        override val kind get() = ShellPageKind.Canvas
    }

    /** An agent's memory: the block graph and its MemFS files. */
    data class Memory(override val agentId: String? = null) : ShellDestination {
        override val kind get() = ShellPageKind.Memory
    }

    data class Schedules(override val agentId: String? = null) : ShellDestination {
        override val kind get() = ShellPageKind.Schedules
    }

    data object Channels : ShellDestination {
        override val kind get() = ShellPageKind.Channels
    }

    /** The Skills page, whose Tools section is the [ShellSkillsSection.Tools] anchor (zxk2n.32). */
    data class Skills(
        override val agentId: String? = null,
        val section: ShellSkillsSection = ShellSkillsSection.Skills,
    ) : ShellDestination {
        override val kind get() = ShellPageKind.Skills
    }

    data class Settings(val section: ShellSettingsSection? = null) : ShellDestination {
        override val kind get() = ShellPageKind.Settings
    }

    /** The agent editor for [agentId]. */
    data class AgentEditor(override val agentId: String) : ShellDestination {
        override val kind get() = ShellPageKind.AgentEditor
    }

    /** Provider accounts, API keys and the models the picker shows. */
    data object Providers : ShellDestination {
        override val kind get() = ShellPageKind.Providers
    }

    /** Launch status and backend configuration (desktop diagnostics). */
    data object Overview : ShellDestination {
        override val kind get() = ShellPageKind.Overview
    }
}

/** One per [ShellDestination] subtype: the page that draws it. */
enum class ShellPageKind { Home, Chat, Canvas, Memory, Schedules, Channels, Skills, Settings, AgentEditor, Providers, Overview }

/** Where the Skills page opens. */
enum class ShellSkillsSection { Skills, Tools }

/** A Settings anchor. A pairing link opened from outside lands on [Pairing]; its secret never rides the route. */
enum class ShellSettingsSection { Pairing }

/** The Skills page section of this destination: its anchor on Skills, else the default. */
val ShellDestination.skillsSection: ShellSkillsSection
    get() = (this as? ShellDestination.Skills)?.section ?: ShellSkillsSection.Skills

/** The Settings anchor of this destination, if it is Settings with one. */
val ShellDestination.settingsSection: ShellSettingsSection?
    get() = (this as? ShellDestination.Settings)?.section

private val LensSections: Map<ShellPageKind, LensDestination> = mapOf(
    ShellPageKind.Memory to LensDestination.Memory,
    ShellPageKind.Schedules to LensDestination.Schedules,
    ShellPageKind.Channels to LensDestination.Channels,
    ShellPageKind.Skills to LensDestination.Skills,
    ShellPageKind.Chat to LensDestination.Conversations,
)

/** The drawer's section for this destination, if it is one of the agent panel's sections. */
val ShellDestination.lensSection: LensDestination? get() = LensSections[kind]

/** The destination a drawer section opens, for the focused [agentId]. */
fun LensDestination.toShellDestination(agentId: String?): ShellDestination = when (this) {
    LensDestination.Memory -> ShellDestination.Memory(agentId)
    LensDestination.Schedules -> ShellDestination.Schedules(agentId)
    LensDestination.Channels -> ShellDestination.Channels
    LensDestination.Skills -> ShellDestination.Skills(agentId)
    LensDestination.Conversations -> ShellDestination.Chat(agentId)
}
