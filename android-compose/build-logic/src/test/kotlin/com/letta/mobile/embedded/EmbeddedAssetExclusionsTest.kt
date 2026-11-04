package com.letta.mobile.embedded

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals

class EmbeddedAssetExclusionsTest {
    @TempDir
    lateinit var modules: File

    @Test
    fun `staging excludes host binaries cursor worker and duplicate wasm but retains android fallback`() {
        val nested = "@letta-ai/letta-code/node_modules"
        val excluded = listOf(
            "@letta-ai/letta-code/cursor-worker.js",
            "$nested/@cursor/sdk/dist/esm/index.js",
            "$nested/@cursor/sdk-darwin-arm64/binary",
            "$nested/@cursor/sdk-darwin-x64/binary",
            "$nested/@cursor/sdk-linux-arm64/binary",
            "$nested/@cursor/sdk-linux-x64/binary",
            "$nested/@cursor/sdk-win32-x64/binary",
            "$nested/@img/sharp-freebsd-wasm32/node_modules/@img/sharp-wasm32/sharp.wasm",
            "$nested/@img/sharp-webcontainers-wasm32/node_modules/@img/sharp-wasm32/sharp.wasm",
            "$nested/@img/sharp-wasm32/sharp.wasm",
            "$nested/@img/sharp-libvips-linuxmusl-arm64/lib.so",
            "$nested/@img/sharp-libvips-linux-ppc64/lib.so",
            "$nested/@img/sharp-libvips-linux-s390x/lib.so",
            "$nested/@img/sharp-libvips-linux-riscv64/lib.so",
            "$nested/@img/sharp-linuxmusl-x64/sharp.node",
            "$nested/@img/sharp-win32-ia32/sharp.node",
            "$nested/.bin/rg",
            "$nested/.bin/pi-ai",
        )
        val kept = listOf("@img/sharp-wasm32/sharp.wasm", "@letta-ai/letta-code/letta.js")
        (excluded + kept).forEach { path ->
            modules.resolve(path).apply { parentFile.mkdirs(); writeText("fixture") }
        }

        val project = ProjectBuilder.builder().build()
        val copied = project.fileTree(modules).matching { exclude(HOST_ONLY_NODE_MODULES) }
            .files.map { it.relativeTo(modules).invariantSeparatorsPath }.toSet()

        assertEquals(kept.toSet(), copied)
    }
}
