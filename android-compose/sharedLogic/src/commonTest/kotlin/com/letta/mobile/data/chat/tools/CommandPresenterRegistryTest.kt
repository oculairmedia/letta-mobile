package com.letta.mobile.data.chat.tools

import com.letta.mobile.data.chat.tools.bundled.BundledToolPresenters
import com.letta.mobile.data.chat.tools.shell.ShellTools
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** letta-mobile-2ccpv: a shell call reads through the command presenter of the CLI it runs, else as Bash. */
class CommandPresenterRegistryTest {
    private fun bash(command: String, result: String? = null) =
        ToolCallFacts("Bash", """{"command":${JsonPrimitive(command)}}""", result)

    private val deploy = CommandPresenterSpec(
        program = "acme",
        subcommands = listOf("deploy"),
        preview = CommandPreviewSpec(ToolPreviewKind.Task, flag = "--job"),
    )

    private val registry = BundledToolPresenters.registry.contribute("acme.cli", tools = emptyMap(), commands = mapOf("deploy" to deploy))

    @Test
    fun anUnclaimedCommandReadsAsRanWithItsCommandAndOutput() {
        val facts = bash("ls -la\npwd", result = "a.txt")
        val line = registry.line(facts)
        assertEquals(ToolVerb.Ran, line.verb)
        assertEquals("ls -la", line.target)
        assertEquals("bash", line.presenterId)
        val inset = assertIs<ToolInset.Command>(registry.inset(facts))
        assertEquals("ls -la\npwd", inset.command)
        assertEquals(listOf(ToolOutputStream(ToolOutputStream.Kind.Output, "a.txt")), inset.output)
    }

    @Test
    fun aNonZeroExitFailsTheLineAndTurnsTheOutputToStderr() {
        val facts = bash("make", result = "boom\nExit code: 2")
        assertEquals(ToolFailure(2), registry.line(facts).failure)
        assertEquals(ToolOutputStream.Kind.Stderr, assertIs<ToolInset.Command>(registry.inset(facts)).output.single().kind)
    }

    @Test
    fun aClaimedCommandReadsThroughItsPresenterEvenInsideAWrapperAndAfterOtherCommands() {
        val line = registry.line(bash("cd /srv && bash -lc 'FOO=1 /usr/bin/acme deploy --job j-7'"))
        assertEquals("acme.cli/deploy", line.presenterId)
        assertEquals("/usr/bin/acme deploy --job j-7", line.target)
        assertEquals(listOf(ToolPreviewTarget.TaskOutput("j-7")), line.previews)
    }

    @Test
    fun aCommandMatchesOnItsProgramAndSubcommandsOnly() {
        assertEquals("bash", registry.line(bash("acme status")).presenterId)
        assertEquals("bash", registry.line(bash("echo acme deploy")).presenterId)
    }

    @Test
    fun moreSubcommandsIsMoreSpecific() {
        val general = CommandPresenterSpec(program = "acme")
        val withBoth = registry.contribute("acme.more", tools = emptyMap(), commands = mapOf("any" to general))
        assertEquals("acme.cli/deploy", withBoth.line(bash("acme deploy")).presenterId)
        assertEquals("acme.more/any", withBoth.line(bash("acme status")).presenterId)
    }

    @Test
    fun aScriptThatNamesNoRegisteredProgramIsRejectedAndAnUnreadableOneHasNoCommands() {
        assertNull(registry.commands.find("echo 'unterminated"))
        assertEquals(emptyList(), ShellTools.commandsOf("acme deploy 'unterminated"))
        assertNull(registry.commands.find("acme deploy 'unterminated"))
    }

    @Test
    fun anArgvCommandReadsAsItsScript() {
        val facts = ToolCallFacts("shell", """{"command":["bash","-lc","git status"],"workdir":"/repo"}""", null)
        val inset = assertIs<ToolInset.Command>(registry.inset(facts))
        assertEquals("git status", inset.command)
        assertEquals("/repo", inset.workingDirectory)
    }
}
