package com.letta.mobile.data.plugin

import com.letta.mobile.data.schema.SchemaProblem

/**
 * A manifest's `runtime` (plan section 3.1): its files inside the package, and its templates
 * (`${settings.*}`/`${secrets.*}` only in a process's env and a service's headers, each naming a
 * declared setting or secret; a secret never in a URL, nothing in a command line).
 */
internal object PluginRuntimeRules {
    private const val CURRENT_DIR = "."
    private val RUNTIME = ManifestPointer.ROOT / "runtime"

    fun check(manifest: PluginManifest): List<SchemaProblem> = when (val runtime = manifest.runtime) {
        is PluginRuntime.Jvm -> jvm(runtime)
        is PluginRuntime.Process -> process(manifest, runtime)
        is PluginRuntime.Service -> service(manifest, runtime)
    }

    private fun jvm(runtime: PluginRuntime.Jvm): List<SchemaProblem> = listOfNotNull(
        PluginManifestProblem.BAD_PATH.at(RUNTIME / "jar", "the jar is a .jar file inside the package")
            .takeIf { !PluginPackagePaths.isInside(runtime.jar) || !runtime.jar.endsWith(".jar") },
    )

    private fun process(manifest: PluginManifest, runtime: PluginRuntime.Process): List<SchemaProblem> {
        val command = runtime.command.removePrefix("./")
        val commandProblem = PluginManifestProblem.BAD_PATH.at(RUNTIME / "command", "a command with a path names a file inside the package (or is a bare program name)")
            .takeIf { hasPath(command) && !PluginPackagePaths.isInside(command) }
        val cwdProblem = PluginManifestProblem.BAD_PATH.at(RUNTIME / "cwd", "the working directory is inside the package")
            .takeIf { runtime.cwd != CURRENT_DIR && !PluginPackagePaths.isInside(runtime.cwd) }
        val commandLine = (listOf(runtime.command) + runtime.args).mapIndexedNotNull { index, part ->
            val at = if (index == 0) RUNTIME / "command" else RUNTIME / "args" / (index - 1)
            PluginManifestProblem.BAD_TEMPLATE.at(at, "templates go in env, never on the command line").takeIf { part.contains(PluginTemplates.OPEN) }
        }
        return listOfNotNull(commandProblem, cwdProblem) + commandLine + templates(manifest, runtime.env, RUNTIME / "env")
    }

    private fun hasPath(command: String): Boolean = command.any { it == '/' || it == '\\' }

    private fun service(manifest: PluginManifest, runtime: PluginRuntime.Service): List<SchemaProblem> {
        val at = RUNTIME / "url"
        val scan = PluginTemplates.scan(runtime.url)
        val secretInUrl = PluginManifestProblem.SECRET_IN_URL.at(at, "a secret never goes in a URL; send it in a header")
            .takeIf { scan.refs.any { it.namespace == TemplateNamespace.SECRETS } }
        return listOfNotNull(secretInUrl) + references(manifest, scan, at) + templates(manifest, runtime.headers, RUNTIME / "headers")
    }

    /** Each value of [values] (env or headers) at `[at]/<key>`: well-formed templates naming declared settings and secrets. */
    private fun templates(manifest: PluginManifest, values: Map<String, String>, at: ManifestPointer): List<SchemaProblem> =
        values.flatMap { (key, text) -> references(manifest, PluginTemplates.scan(text), at / key) }

    private fun references(manifest: PluginManifest, scan: TemplateScan, at: ManifestPointer): List<SchemaProblem> {
        val malformed = scan.malformed.map {
            PluginManifestProblem.BAD_TEMPLATE.at(at, "'$it' is not a template; use \${settings.<name>} or \${secrets.<name>}")
        }
        val undeclared = scan.refs.filterNot { declares(manifest, it) }.map {
            PluginManifestProblem.UNDECLARED_REFERENCE.at(at, "$it names nothing the manifest declares in ${it.namespace.wire}")
        }
        return malformed + undeclared
    }

    private fun declares(manifest: PluginManifest, ref: TemplateRef): Boolean = when (ref.namespace) {
        TemplateNamespace.SETTINGS -> ref.name in manifest.settings
        TemplateNamespace.SECRETS -> manifest.secrets.any { it.name == ref.name }
    }
}
