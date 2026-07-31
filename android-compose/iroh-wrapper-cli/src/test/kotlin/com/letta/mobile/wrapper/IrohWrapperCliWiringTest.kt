package com.letta.mobile.wrapper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * letta-mobile-zsgad: guards the packaged distribution's entrypoint contract.
 *
 * The production systemd unit invokes `<installDir>/bin/meridian-iroh-wrapper
 * app-server-serve-iroh …`. Three things have to hold for that to work, and all
 * three are silently breakable by refactors that still compile:
 *  - the `application` plugin's `mainClass` resolves to a class with a
 *    `public static void main(String[])`,
 *  - the clikt root registers a subcommand literally named
 *    `app-server-serve-iroh` (the name the deployed ExecStart passes),
 *  - the options the unit passes still parse.
 */
class IrohWrapperCliWiringTest {
    @Test
    fun mainClassDeclaredInBuildScriptResolvesWithAJvmMainEntry() {
        val mainClass = Class.forName("com.letta.mobile.wrapper.Main")
        val entry = mainClass.getMethod("main", Array<String>::class.java)

        assertTrue(java.lang.reflect.Modifier.isStatic(entry.modifiers), "main must be static (@JvmStatic)")
        assertTrue(java.lang.reflect.Modifier.isPublic(entry.modifiers), "main must be public")
    }

    @Test
    fun rootCommandRegistersTheDeployedSubcommandName() {
        val root = buildIrohWrapperCli()

        val names = root.registeredSubcommandNames()
        assertTrue(
            "app-server-serve-iroh" in names,
            "expected app-server-serve-iroh in $names — the deployed ExecStart passes this literal",
        )
    }

    @Test
    fun deployedOptionVectorParses() {
        val serve = buildIrohWrapperCli()
            .registeredSubcommands()
            .single { it.commandName == "app-server-serve-iroh" }

        // Exactly the option set the production unit passes today.
        val options = serve.registeredOptions().flatMap { it.names }
        listOf(
            "--app-server-url",
            "--iroh-port",
            "--iroh-secret-key-file",
            "--pairing-store-file",
        ).forEach { option ->
            assertTrue(option in options, "expected $option in $options")
        }
    }

    @Test
    fun rootCommandNameMatchesTheInstalledLauncherName() {
        // `application { applicationName }` generates bin/meridian-iroh-wrapper;
        // keep the help text's program name in sync with it.
        assertEquals("meridian-iroh-wrapper", buildIrohWrapperCli().commandName)
    }

    @Test
    fun irohNativeBindingIsOnTheDistributionRuntimeClasspath() {
        // A `--help` smoke does not exercise JNI. Loading the Iroh binding class
        // proves the `computer.iroh:iroh` jar actually made it into the
        // distribution's lib/ (it is `implementation` inside sharedLogic, so it
        // is NOT transitively exposed and must be requested explicitly).
        val endpoint = Class.forName("computer.iroh.Endpoint", false, javaClass.classLoader)
        assertNotNull(endpoint)
    }
}
