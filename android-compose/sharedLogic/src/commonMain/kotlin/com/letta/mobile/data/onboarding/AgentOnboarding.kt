package com.letta.mobile.data.onboarding

/** A first-run setup task offered to a fresh agent. */
enum class OnboardingTaskKind { SetPersona, ConnectChannel, AddSkills }

/** One row in the first-run checklist (icon/action resolved per platform). */
data class OnboardingTask(
    val kind: OnboardingTaskKind,
    val title: String,
    val subtitle: String,
)

/**
 * Shared content for the agent first-run / onboarding empty state (Penpot
 * "Desktop · New agent first-run" + the mobile Onboard boards): the greeting,
 * the setup checklist, and the "or just start chatting" starter prompts.
 *
 * Lives in commonMain so the desktop first-run screen and the mobile onboarding
 * render the same copy and the same task set from one source of truth. Platforms
 * supply the icons and map each [OnboardingTaskKind] to a navigation action.
 */
object AgentOnboarding {
    fun greeting(agentName: String?): String =
        "Hi, I'm ${agentName?.takeIf { it.isNotBlank() } ?: "your agent"}"

    const val SUBTITLE: String = "I'm a fresh agent — no memory yet. Here's how to get started:"

    const val STARTER_HEADER: String = "Or just start chatting"

    /** The setup checklist, with [agentName] woven into each subtitle. */
    fun tasks(agentName: String?): List<OnboardingTask> {
        val name = agentName?.takeIf { it.isNotBlank() } ?: "your agent"
        return listOf(
            OnboardingTask(
                kind = OnboardingTaskKind.SetPersona,
                title = "Set persona & backstory",
                subtitle = "Give $name a voice and context",
            ),
            OnboardingTask(
                kind = OnboardingTaskKind.ConnectChannel,
                title = "Connect a channel",
                subtitle = "Reach you on Slack or Telegram",
            ),
            OnboardingTask(
                kind = OnboardingTaskKind.AddSkills,
                title = "Add skills",
                subtitle = "Give $name tools it can use",
            ),
        )
    }

    /** Tappable starter prompts that pre-fill the composer. */
    val starterPrompts: List<String> = listOf(
        "Tell me about yourself",
        "What can you do?",
        "Explore my files",
    )
}
