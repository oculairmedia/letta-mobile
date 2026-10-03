package com.letta.mobile.plugin.api

import java.io.File
import java.lang.reflect.Member
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Holds the public API of `:plugin-api` equal to the committed dump `api/plugin-api.api`, so every
 * change to what plugin authors compile against is deliberate and reviewed (binary compatibility:
 * a minor may only add). Regenerate with
 * `./gradlew :plugin-api:jvmTest -PpluginApi.updateDump=true` and review the diff.
 */
class PluginApiStabilityTest {
    @Test
    fun `the public API matches the committed dump`() {
        val dumpFile = File(requireNotNull(System.getProperty("pluginApi.dump")) { "pluginApi.dump is set by the build" })
        val actual = PublicApiDump.of(PluginApi::class.java)
        if (System.getProperty("pluginApi.updateDump") == "true") dumpFile.writeText(actual)
        assertTrue(dumpFile.exists(), "missing ${dumpFile.path}; run with -PpluginApi.updateDump=true")
        assertEquals(dumpFile.readText().replace("\r\n", "\n"), actual, "the public API changed; review it and update the dump")
    }

    @Test
    fun `the published version is the SPI version and its major is the contract version`() {
        assertEquals(System.getProperty("pluginApi.version"), PluginApi.VERSION)
        assertEquals(PluginApi.VERSION.substringBefore('.').toInt(), PluginApi.CONTRACT_VERSION)
    }
}

/** A deterministic text dump of the public classes and members of one package's compiled classes. */
private object PublicApiDump {
    fun of(anchor: Class<*>): String {
        val packagePath = anchor.packageName.replace('.', '/')
        val root = File(anchor.protectionDomain.codeSource.location.toURI())
        return root.resolve(packagePath).listFiles().orEmpty()
            .filter { it.name.endsWith(".class") && !it.name.endsWith("\$\$serializer.class") }
            .map { Class.forName(anchor.packageName + "." + it.name.removeSuffix(".class"), false, anchor.classLoader) }
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
            .sortedBy { it.name }
            .joinToString("") { classDump(it) }
    }

    private fun classDump(type: Class<*>): String {
        val members = (type.declaredConstructors.toList() + type.declaredMethods + type.declaredFields)
            .filter { visible(it) }
            .map { "    ${signature(it)}" }
            .sorted()
        val supertypes = (listOfNotNull(type.genericSuperclass) + type.genericInterfaces).joinToString { it.typeName }
        return "${Modifier.toString(type.modifiers)} ${type.name} : $supertypes\n" + members.joinToString("") { "$it\n" } + "\n"
    }

    private fun visible(member: Member): Boolean =
        !member.isSynthetic && (Modifier.isPublic(member.modifiers) || Modifier.isProtected(member.modifiers))

    private fun signature(member: Member): String = when (member) {
        is java.lang.reflect.Method -> member.toGenericString()
        is java.lang.reflect.Constructor<*> -> member.toGenericString()
        is java.lang.reflect.Field -> member.toGenericString()
        else -> member.toString()
    }
}
