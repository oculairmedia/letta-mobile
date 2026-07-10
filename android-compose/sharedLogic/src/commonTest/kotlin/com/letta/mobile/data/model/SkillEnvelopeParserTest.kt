package com.letta.mobile.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SkillEnvelopeParserTest {
    // Real captured envelope from asus-router skill
    private val realEnvelope = """
        <asus-router>
        name: asus-router
        description: Pull stats from ASUS RT-AX82U router on demand — connected clients, CPU, memory, WAN, traffic, WiFi, VPN status. Use when the user asks about router stats, network devices, connected clients, bandwidth usage, or router health.
        ---
        
        ## Usage
        
        ### Commands
        
        ```bash
        asus-router status
        asus-router clients
        ```
        
        ### Example output
        
        | Metric | Value |
        |--------|-------|
        | CPU    | 12%   |
        | Memory | 45%   |
        
        ARGUMENTS: summary
        </asus-router>
    """.trimIndent()

    @Test
    fun parseRealEnvelope() {
        val parsed = parseSkillEnvelope(realEnvelope)
        
        assertNotNull(parsed, "Should parse real envelope")
        assertEquals("asus-router", parsed.slug)
        assertEquals("asus-router", parsed.name)
        assertEquals(
            "Pull stats from ASUS RT-AX82U router on demand — connected clients, CPU, memory, WAN, traffic, WiFi, VPN status. Use when the user asks about router stats, network devices, connected clients, bandwidth usage, or router health.",
            parsed.description
        )
        assertEquals("summary", parsed.args)
        assertEquals(realEnvelope, parsed.rawContent)
    }

    @Test
    fun parseMinimalEnvelope() {
        val minimal = """
            <test-skill>
            name: test-skill
            description: A test skill
            ---
            Some content
            ARGUMENTS: foo
            </test-skill>
        """.trimIndent()

        val parsed = parseSkillEnvelope(minimal)
        
        assertNotNull(parsed)
        assertEquals("test-skill", parsed.slug)
        assertEquals("test-skill", parsed.name)
        assertEquals("A test skill", parsed.description)
        assertEquals("foo", parsed.args)
    }

    @Test
    fun parseEnvelopeWithMultilineDescription() {
        val envelope = """
            <multi-skill>
            name: multi-skill
            description: First line.
              Second line continuation.
            ---
            Content
            ARGUMENTS: bar
            </multi-skill>
        """.trimIndent()

        val parsed = parseSkillEnvelope(envelope)
        
        assertNotNull(parsed)
        assertEquals("multi-skill", parsed.slug)
        assertEquals("multi-skill", parsed.name)
        // Parser should capture the first line only
        assertEquals("First line.", parsed.description.split("\n")[0])
        assertEquals("bar", parsed.args)
    }

    @Test
    fun parseEnvelopeNoArguments() {
        val noArgs = """
            <no-args>
            name: no-args
            description: No arguments skill
            ---
            Content
            </no-args>
        """.trimIndent()

        val parsed = parseSkillEnvelope(noArgs)
        
        assertNotNull(parsed)
        assertEquals("no-args", parsed.slug)
        assertEquals("no-args", parsed.name)
        assertEquals("No arguments skill", parsed.description)
        assertEquals("", parsed.args)
    }

    @Test
    fun parseInvalidContentReturnsNull() {
        assertNull(parseSkillEnvelope("not a skill envelope"))
        assertNull(parseSkillEnvelope(""))
        assertNull(parseSkillEnvelope("<skill>incomplete"))
        assertNull(parseSkillEnvelope("no tags at all"))
    }

    @Test
    fun parseMissingClosingTag() {
        val noClosing = """
            <test>
            name: test
            description: Test
            ---
            ARGUMENTS: foo
        """.trimIndent()

        assertNull(parseSkillEnvelope(noClosing), "Should return null for missing closing tag")
    }

    @Test
    fun parseMismatchedTags() {
        val mismatched = """
            <skill-a>
            name: skill-a
            description: Test
            ---
            ARGUMENTS: foo
            </skill-b>
        """.trimIndent()

        assertNull(parseSkillEnvelope(mismatched), "Should return null for mismatched tags")
    }
}
