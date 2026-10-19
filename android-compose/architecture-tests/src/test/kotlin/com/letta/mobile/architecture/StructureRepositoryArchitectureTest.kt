package com.letta.mobile.architecture

import org.junit.jupiter.api.Test
import java.nio.file.Path

class StructureRepositoryArchitectureTest {
    @Test
    fun `export parsed production structure`() {
        val root = Path.of(requireNotNull(System.getProperty("architecture.projectRoot")))
        StructureSourceExport.write(root, KotlinSourcePolicy.productionScope(root).files)
    }
}
