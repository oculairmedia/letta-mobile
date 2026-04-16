package com.letta.mobile.bot.skills

import io.kotest.core.spec.style.WordSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.time.DayOfWeek
import java.time.LocalDate

class BotSkillRegistryTest : WordSpec({
    "BotSkillRegistry" should {
        "resolve only enabled skills that are active for the current date" {
            val registry = BotSkillRegistry(
                skillLoader = {
                    listOf(
                        BotSkill(
                            id = "always-on",
                            displayName = "Always On",
                            description = "",
                            promptFragment = "Always",
                            localToolNames = setOf("render_summary_card"),
                        ),
                        BotSkill(
                            id = "weekdays-only",
                            displayName = "Weekdays",
                            description = "",
                            promptFragment = "Weekdays",
                            localToolNames = setOf("render_suggestion_chips"),
                            activationRule = BotSkillActivationRule.WeekdayOnly(
                                weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY)
                            ),
                        ),
                    )
                }
            )

            val mondaySkills = registry.resolveEnabledSkills(
                skillIds = listOf("always-on", "weekdays-only"),
                date = LocalDate.of(2026, 4, 13),
            )
            val sundaySkills = registry.resolveEnabledSkills(
                skillIds = listOf("always-on", "weekdays-only"),
                date = LocalDate.of(2026, 4, 12),
            )

            mondaySkills shouldHaveSize 2
            sundaySkills shouldHaveSize 1
            sundaySkills.first().id shouldBe "always-on"
        }

        "list bundled skills in stable id order" {
            val registry = BotSkillRegistry(
                skillLoader = {
                    listOf(
                        BotSkill("zeta", "Zeta", "", "", emptySet()),
                        BotSkill("alpha", "Alpha", "", "", emptySet()),
                    )
                }
            )

            val ids = registry.listAvailableSkills().map { it.id }

            ids shouldContain "alpha"
            ids.first() shouldBe "alpha"
        }

        "report unknown configured skill ids" {
            val registry = BotSkillRegistry(
                skillLoader = {
                    listOf(
                        BotSkill("morning-briefing", "Morning Briefing", "", "", emptySet()),
                        BotSkill("commute-assistant", "Commute Assistant", "", "", emptySet()),
                    )
                }
            )

            val unknownIds = registry.findUnknownSkillIds(
                listOf("morning-briefing", " missing-skill ", "", "missing-skill", "other-skill")
            )

            unknownIds shouldBe listOf("missing-skill", "other-skill")
        }
    }
})
