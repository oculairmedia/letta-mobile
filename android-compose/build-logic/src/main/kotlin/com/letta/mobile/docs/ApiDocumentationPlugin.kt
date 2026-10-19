package com.letta.mobile.docs

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.dokka.gradle.DokkaExtension
import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier
import java.net.URI

/** Shared API reference settings; decisions and runbooks remain hand-written. */
class ApiDocumentationPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("org.jetbrains.dokka")
        project.pluginManager.apply(ApiContractPlugin::class.java)
        val dokka = project.extensions.getByType(DokkaExtension::class.java)
        val moduleOverview = project.layout.projectDirectory.file("Module.md").asFile
        val reportWholeModule = project.path in setOf(":core:runtime", ":plugin-api")
        val reportExtensionPackages = project.path == ":sharedLogic"
        dokka.moduleName.set(if (project == project.rootProject) "Letta Mobile API" else project.path)
        dokka.dokkaPublications.named("html") {
            outputDirectory.set(project.layout.buildDirectory.dir("dokka/html"))
            if (project == project.rootProject && moduleOverview.isFile) includes.from(moduleOverview)
            // Missing KDocs are gated by the shrinking Dokka diagnostic baseline.
            failOnWarning.set(false)
        }
        dokka.dokkaSourceSets.configureEach {
            if (moduleOverview.isFile) includes.from(moduleOverview)
            documentedVisibilities.set(setOf(VisibilityModifier.Public, VisibilityModifier.Protected))
            reportUndocumented.set(reportWholeModule)
            if (reportExtensionPackages) {
                perPackageOption {
                    matchingRegex.set("com\\.letta\\.mobile\\.(data\\.(controller\\.extras|timeline|transport\\.(api|appserver))|ui\\.chat\\.session)(\\..*)?")
                    reportUndocumented.set(true)
                }
            }
            suppressGeneratedFiles.set(true)
            sourceLink {
                localDirectory.set(project.rootProject.projectDir.parentFile)
                val ref = project.providers.gradleProperty("dokkaSourceRef").orElse("main")
                remoteUrl.set(ref.map { URI("https://github.com/oculairmedia/letta-mobile/tree/$it") })
                remoteLineSuffix.set("#L")
            }
        }
        if (reportWholeModule || reportExtensionPackages) {
            // Cached HTML does not replay warnings; only diagnostic-producing tasks rerun.
            project.tasks.matching { it.name == "dokkaGenerateModuleHtml" }.configureEach {
                doNotTrackState("Dokka diagnostics must be emitted freshly, not replayed from cached HTML")
            }
        }
    }
}
