package com.letta.mobile.data.plugin

import com.letta.mobile.data.schema.SchemaProblem

/**
 * A manifest's `runtime` (plan section 3.1): its files inside the package, and its templates
 * (`${settings.*}`/`${secrets.*}` only in a process's env and a service's headers, each naming a
 * declared setting or secret; a secret never in a URL, nothing in a command line).
 */
internal object PluginRuntimeRules {
    private const val CURRENT_DIR = "."

    fun check(manifest: PluginManifest): List<SchemaProblem> = when (val runtime = manifest.runtime) {
        is PluginRuntime.Jvm -> jvm(runtime)
        is PluginRuntime.Process -> process(manifest, runtime)
        is PluginRuntime.Service -> service(manifest, runtime)
    }

    private fun jvm(runtime: PluginRuntime.Jvm): List<SchemaProblem> = listOfNotNull(
        badPath(pointer("runtime", "jar"), "the jar is a .jar file inside the package")
            .takeIf { !PluginPackagePaths.isInside(runtime.jar) || !runtime.jar.endsWith(".jar") },
    )

    private fun process(manifest: PluginManifest, runtime: PluginRuntime.Process): List<SchemaProblem> {
        val command = runtime.command.removePrefix("./")
        val commandProblem = badPath(pointer("runtime", "command"), "a command with a path names a file inside the package (or is a bare program name)")
            .takeIf { command.any { it == '/' || it == '\\' } && !PluginPackagePaths.isInside(command) }
        val cwdProblem = badPath(pointer("runtime", "cwd"), "the working directory is inside the package")
            .takeIf { runtime.cwd != CURRENT_DIR && !PluginPackagePaths.isInside(runtime.cwd) }
        val argProblems = runtime.args.mapIndexedNotNull { index, arg ->
            problem(pointer("runtime", "args", index), PluginManifestProblem.BAD_TEMPLATE, "templates go in env, never on the command line")
                .takeIf { PluginTemplates.scan(arg).let { it.refs.isNotEmpty() || it.malformed.isNotEmpty() } }
        }
        val commandTemplate = problem(pointer("runtime", "command"), PluginManifestProblem.BAD_TEMPLATE, "templates go in env, never in the command")
            .takeIf { runtime.command.contains("\${") }
        return listOfNotNull(commandProblem, cwdProblem, commandTemplate) + argProblems +
            templates(manifest, runtime.env, pointer("runtime", "env"))
    }

    private fun service(manifest: PluginManifest, runtime: PluginRuntime.Service): List<SchemaProblem> {
        val path = pointer("runtime", "url")
        val scan = PluginTemplates.scan(runtime.url)
        val secretInUrl = problem(path, PluginManifestProblem.SECRET_IN_URL, "a secret never goes in a URL; send it in a header")
            .takeIf { scan.refs.any { it.namespace == TemplateNamespace.SECRETS } }
        return listOfNotNull(secretInUrl) + references(manifest, scan, path) + templates(manifest, runtime.headers, pointer("runtime", "headers"))
    }

    /** Each value of [values] (env or headers) at `[path]/<key>`: well-formed templates naming declared settings and secrets. */
    private fun templates(manifest: PluginManifest, values: Map<String, String>, path: String): List<SchemaProblem> =
        values.flatMap { (key, text) -> references(manifest, PluginTemplates.scan(text), path + pointer(key)) }

    private fun references(manifest: PluginManifest, scan: TemplateScan, path: String): List<SchemaProblem> {
        val malformed = scan.malformed.map {
            problem(path, PluginManifestProblem.BAD_TEMPLATE, "'$it' is not a template; use \${settings.<name>} or \${secrets.<name>}")
        }
        val undeclared = scan.refs.filterNot { declares(manifest, it) }.map {
            problem(path, PluginManifestProblem.UNDECLARED_REFERENCE, "$it names nothing the manifest declares in ${it.namespace.wire}")
        }
        return malformed + undeclared
    }

    private fun declares(manifest: PluginManifest, ref: TemplateRef): Boolean = when (ref.namespace) {
        TemplateNamespace.SETTINGS -> ref.name in manifest.settings
        TemplateNamespace.SECRETS -> manifest.secrets.any { it.name == ref.name }
    }

    private fun badPath(path: String, message: String) = problem(path, PluginManifestProblem.BAD_PATH, message)
}
