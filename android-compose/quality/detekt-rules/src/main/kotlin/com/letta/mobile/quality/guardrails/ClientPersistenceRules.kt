package com.letta.mobile.quality.guardrails

import io.gitlab.arturbosch.detekt.api.Config
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType

/**
 * Whether this file may persist on a client: the client store (a package with a `clientstore`
 * segment), or host code, whose state store is where shell and user state lives.
 */
internal fun KtFile.mayPersistClientState(): Boolean {
    val name = packageFqName.asString()
    return packageFqName.pathSegments().any { it.asString() == "clientstore" } ||
        HOST_PACKAGES.any { name == it || name.startsWith("$it.") }
}

// The host side: the node (server host), its durable state files, and the on-device runtime host.
private val HOST_PACKAGES = listOf(
    "com.letta.mobile.data.controller.node",
    "com.letta.mobile.data.hoststate",
    "com.letta.mobile.runtime.local",
)

/**
 * Deny by default: clients keep no persistent state. Preferences, pins, layout choices, drafts,
 * history and settings come from the host's shell state document. Reported outside the client store
 * and host packages: DataStore and Preferences, SharedPreferences, settings libraries,
 * java.util.prefs, Room and SQL databases, browser localStorage, sessionStorage and IndexedDB, the
 * app's state, data and cache directories, and the shared settings and key-value store ports.
 */
internal class ClientPersistenceOutsideClientStore(config: Config = Config.empty) : ProductionSourceRule(
    config,
    "ClientPersistenceOutsideClientStore",
    "Clients persist nothing outside the client store; shell and user state comes from the host.",
) {
    override fun visitKtFile(file: KtFile) {
        if (file.mayPersistClientState()) return
        super.visitKtFile(file)
    }

    override fun visitImportDirective(importDirective: KtImportDirective) {
        super.visitImportDirective(importDirective)
        val path = importDirective.importPath?.pathStr ?: return
        if (PERSISTENCE_IMPORTS.none(path::startsWith)) return
        report(importDirective, "Do not import $path here; $ADVICE")
    }

    override fun visitReferenceExpression(expression: KtReferenceExpression) {
        super.visitReferenceExpression(expression)
        val name = (expression as? KtNameReferenceExpression)?.getReferencedName() ?: return
        if (name !in PERSISTENCE_NAMES || expression.getParentOfType<KtImportDirective>(strict = true) != null) return
        report(expression, "Do not use $name here; $ADVICE")
    }

    private companion object {
        const val ADVICE = "clients keep no state. Read and propose it through the host's ShellStateDocument; " +
            "only connection bootstrap, disposable caches and justified device facts live in a clientstore package."
        val PERSISTENCE_IMPORTS = listOf(
            "androidx.datastore.", "android.content.SharedPreferences", "androidx.preference.", "android.preference.",
            "androidx.security.crypto.", "com.russhwolf.settings.", "java.util.prefs.", "androidx.room.",
            "android.database.sqlite.", "androidx.sqlite.", "app.cash.sqldelight.", "java.sql.",
            "org.jetbrains.exposed.",
            "kotlinx.browser.localStorage", "kotlinx.browser.sessionStorage", "org.w3c.dom.Storage",
        )
        val PERSISTENCE_NAMES = setOf(
            // Platform persistence.
            "getSharedPreferences", "getDefaultSharedPreferences", "preferencesDataStore", "localStorage",
            "sessionStorage", "indexedDB", "openOrCreateDatabase", "databaseBuilder",
            // State files: the app's own data, files and cache directories.
            "filesDir", "noBackupFilesDir", "dataDir", "cacheDir", "codeCacheDir", "externalCacheDir",
            "getExternalFilesDir", "getExternalCacheDir", "getDir", "defaultDesktopStateDirectory",
            // The shared client settings and key-value ports.
            "SecureSettingsStore", "KeyValueStore", "SettingsPreferencesStore", "MutableSettingsPreferencesEditor",
            "SettingsPreferencesSnapshot", "JsonPreference",
        )
    }
}

/** Flags a client store device fact with a missing or blank justification. */
internal class DeviceFactWithoutJustification(config: Config = Config.empty) : ProductionSourceRule(
    config,
    "DeviceFactWithoutJustification",
    "A client store device fact needs a non-blank justification.",
) {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val position = JUSTIFICATION_POSITION[expression.calleeExpression?.text] ?: return
        val named = expression.valueArguments.firstOrNull { it.getArgumentName()?.asName?.asString() == JUSTIFICATION }
        val argument = named ?: expression.valueArguments.filter { it.getArgumentName() == null }.getOrNull(position)
        val value = argument?.getArgumentExpression()
        if (value != null && !(value is KtStringTemplateExpression && value.isBlankLiteral())) return
        report(expression, "Justify this device fact: say why the host cannot own it.")
    }

    private fun KtStringTemplateExpression.isBlankLiteral(): Boolean = !hasInterpolation() && text.trim('"').isBlank()

    private companion object {
        const val JUSTIFICATION = "justification"
        val JUSTIFICATION_POSITION = mapOf("DeviceFact" to 0, "deviceFact" to 1)
    }
}
