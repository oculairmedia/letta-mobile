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
            // letta-mobile-bn008.6: the a2a receiver flags must register so the
            // systemd unit can pass them. Regression guard: a refactor that
            // renames or drops them would silently disable the receiver.
            //
            // letta-mobile-xmpqm: --a2a-publish-agents and its LETTA_A2A_PUBLISH_AGENTS
            // env var are GONE — reachability is gated by backend membership,
            // not enumeration of agents at bind. The systemd unit's argv is
            // updated to drop the flag (and the deployment runbook updated to
            // remove the envvar), so this list tracks the post-xmpqm surface.
            "--a2a-port",
            "--a2a-address-book",
            "--a2a-identity-dir",
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

    @Test
    fun virtualBridgeNetworkInterfacesAreFilteredOut() {
        val virtualNames = listOf(
            "docker0",
            "docker_gwbridge",
            "br-123456789abc",
            "br0",
            "veth1234567",
            "virbr0",
            "cni0",
            "flannel.1",
            "tun0",
            "tap0",
            "dummy0",
        )
        virtualNames.forEach { name ->
            assertTrue(
                !com.letta.mobile.cli.commands.isRealNetworkInterfaceName(name),
                "expected virtual interface $name to be filtered out",
            )
        }

        assertTrue(!com.letta.mobile.cli.commands.isRealNetworkInterfaceName("lo", isLoopback = true), "loopback must be filtered out")
        assertTrue(!com.letta.mobile.cli.commands.isRealNetworkInterfaceName("eth0", isUp = false), "down interface must be filtered out")
        assertTrue(!com.letta.mobile.cli.commands.isRealNetworkInterfaceName("ppp0", isPointToPoint = true), "point-to-point must be filtered out")

        val physicalNames = listOf("eth0", "wlan0", "enp3s0", "wlp2s0", "en0", "wl0")
        physicalNames.forEach { name ->
            assertTrue(
                com.letta.mobile.cli.commands.isRealNetworkInterfaceName(name),
                "expected physical interface $name to be accepted",
            )
        }
    }

    @Test
    fun directAddressesSortPhysicalLanIpsBeforeDockerBridgeIps() {
        val addresses = listOf(
            "172.17.0.1:4501",
            "172.18.0.1:4501",
            "192.168.50.90:4501",
            "10.0.0.5:4501",
        )

        val sorted = com.letta.mobile.cli.commands.sortDirectAddresses(addresses)

        val dockerIps = setOf("172.17.0.1:4501", "172.18.0.1:4501")
        val lanIps = setOf("192.168.50.90:4501", "10.0.0.5:4501")

        assertTrue(sorted.take(2).toSet() == lanIps, "Physical LAN IPs must come first: got $sorted")
        assertTrue(sorted.takeLast(2).toSet() == dockerIps, "Docker bridge IPs must come last: got $sorted")
    }
}
