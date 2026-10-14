package com.letta.mobile.embedded

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/** Asserts patches/patch-switch-session-control.mjs landed in the prepared letta.js. */
@DisableCachingByDefault(because = "Verification only; produces no outputs.")
abstract class VerifyEmbeddedLettaCodeSwitchSessionControlTask : DefaultTask() {
    @get:InputDirectory
    abstract val assetsDirectory: DirectoryProperty

    @TaskAction
    fun verify() {
        val lettaJs = assetsDirectory.get().asFile
            .resolve("letta-code/nodejs-project/node_modules/@letta-ai/letta-code/letta.js")
        check(lettaJs.isFile) { "Prepared embedded letta.js not found at ${lettaJs.absolutePath}" }
        val src = lettaJs.readText()
        check(src.contains("letta-mobile-st78v-switch-session-control")) {
            "Prepared embedded letta.js is missing switch_session control handler marker."
        }
        check(src.contains("subtype === \"switch_session\"")) {
            "Prepared embedded letta.js is missing switch_session subtype branch."
        }
    }
}
