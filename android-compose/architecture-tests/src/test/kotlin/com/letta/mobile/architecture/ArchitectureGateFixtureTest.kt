package com.letta.mobile.architecture

import com.lemonappdev.konsist.api.Konsist
import com.tngtech.archunit.core.importer.ClassFileImporter
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class ArchitectureGateFixtureTest {
    private val resources = Path.of(requireNotNull(javaClass.getResource("/konsist")).toURI())

    @Test
    fun `Konsist policy passes a conforming fixture`() {
        val scope = Konsist.scopeFromExternalDirectory(resources.resolve("clean").toString())

        assertTrue(KotlinSourcePolicy.violations(scope).isEmpty())
    }

    @Test
    fun `Konsist policy catches platform imports in commonMain`() {
        val scope = Konsist.scopeFromExternalDirectory(resources.resolve("violation").toString())

        assertTrue(KotlinSourcePolicy.violations(scope).any { "commonMain imports java." in it })
    }

    @Test
    fun `ArchUnit catches a fixture package cycle`() {
        val classes = ClassFileImporter().importPackages("com.letta.mobile.architecture.fixtures.violation.cycle")
        val cycleRule = repositoryBytecodeRules().first()

        assertTrue(cycleRule.evaluate(classes).hasViolation())
    }

    @Test
    fun `ArchUnit accepts an acyclic fixture`() {
        val classes = ClassFileImporter().importPackages("com.letta.mobile.architecture.fixtures.clean")

        assertFalse(repositoryBytecodeRules().any { it.evaluate(classes).hasViolation() })
    }
}
