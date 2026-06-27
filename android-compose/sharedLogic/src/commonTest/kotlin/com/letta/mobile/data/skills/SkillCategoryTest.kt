package com.letta.mobile.data.skills

import kotlin.test.Test
import kotlin.test.assertEquals

class SkillCategoryTest {
    @Test
    fun categorizesByKnownKeywords() {
        assertEquals(SkillCategory.Developer, SkillCategories.categorize("github"))
        assertEquals(SkillCategory.Developer, SkillCategories.categorize("letta-api-client"))
        // datadog/sentry are grouped under Developer in the design mockup.
        assertEquals(SkillCategory.Developer, SkillCategories.categorize("datadog"))
        assertEquals(SkillCategory.Design, SkillCategories.categorize("figma"))
        assertEquals(SkillCategory.Data, SkillCategories.categorize("asus-router", listOf("network")))
        assertEquals(SkillCategory.Data, SkillCategories.categorize("grafana"))
        assertEquals(SkillCategory.Communication, SkillCategories.categorize("slack"))
        assertEquals(SkillCategory.Productivity, SkillCategories.categorize("notion"))
        assertEquals(SkillCategory.Other, SkillCategories.categorize("something-unknown"))
    }

    @Test
    fun groupsInOrderDroppingEmptySections() {
        // github -> Developer, notion -> Productivity, grafana -> Data, figma -> Design.
        val skills = listOf("github", "figma", "notion", "grafana")
        val grouped = SkillCategories.grouped(skills, nameOf = { it })
        assertEquals(
            listOf(SkillCategory.Developer, SkillCategory.Productivity, SkillCategory.Data, SkillCategory.Design),
            grouped.map { it.first },
        )
    }
}
