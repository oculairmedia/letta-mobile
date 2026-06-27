package com.letta.mobile.data.memory

import kotlin.test.Test
import kotlin.test.assertEquals

class MemoryCategoryTest {
    @Test
    fun mapsKnownLabels() {
        assertEquals(MemoryCategory.Persona, MemoryCategories.categorize("persona"))
        assertEquals(MemoryCategory.Human, MemoryCategories.categorize("human"))
        assertEquals(MemoryCategory.Human, MemoryCategories.categorize("user_profile"))
        assertEquals(MemoryCategory.Onboarding, MemoryCategories.categorize("onboarding"))
        assertEquals(MemoryCategory.Project, MemoryCategories.categorize("project_alpha"))
    }

    @Test
    fun caseInsensitiveAndTrimmed() {
        assertEquals(MemoryCategory.Persona, MemoryCategories.categorize("  PERSONA  "))
    }

    @Test
    fun unknownAndBlankFallBackToArchival() {
        assertEquals(MemoryCategory.Archival, MemoryCategories.categorize("something_else"))
        assertEquals(MemoryCategory.Archival, MemoryCategories.categorize(""))
        assertEquals(MemoryCategory.Archival, MemoryCategories.categorize(null))
    }
}
