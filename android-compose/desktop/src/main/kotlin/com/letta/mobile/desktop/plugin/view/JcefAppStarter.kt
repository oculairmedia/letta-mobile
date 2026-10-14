package com.letta.mobile.desktop.plugin.view

import me.friwi.jcefmaven.CefAppBuilder
import me.friwi.jcefmaven.EnumProgress
import org.cef.CefApp
import java.awt.GraphicsEnvironment
import java.io.File

/**
 * Where the JCEF native bundle lives and whether live views may use it.
 *
 * jcefmaven downloads the bundle for this platform (about 100 MB compressed, roughly 300 MB
 * unpacked on Windows x64) into [installDir] the first time a live view asks for it, then reuses
 * it; [cacheDir] is the CEF root cache (each view's own request context is in memory, so nothing
 * of a page is written there). `-Dletta.pluginViews.jcef=false` turns live views off.
 */
internal data class JcefConfig(
    val installDir: File,
    val cacheDir: File,
    val enabled: Boolean,
    val headless: Boolean,
) {
    /** Why live views cannot run on this machine at all, or null when they may try. */
    val disabledReason: String?
        get() = when {
            !enabled -> "Web views are turned off (letta.pluginViews.jcef=false)"
            headless -> "No display for web views"
            else -> null
        }

    companion object {
        fun fromSystem(): JcefConfig {
            val home = File(System.getProperty("user.home") ?: ".", ".letta-mobile")
            val root = System.getProperty("letta.pluginViews.jcefDir")?.let(::File) ?: File(home, "jcef")
            return JcefConfig(
                installDir = File(root, "bundle"),
                cacheDir = File(root, "cache"),
                enabled = System.getProperty("letta.pluginViews.jcef")?.toBooleanStrictOrNull() ?: true,
                headless = GraphicsEnvironment.isHeadless(),
            )
        }
    }
}

/**
 * Starts the process's one [CefApp] through jcefmaven: installs the native bundle when it is
 * missing (reporting progress) and uses windowed rendering (each browser is a heavyweight AWT component the board embeds through
 * SwingPanel). Pages need no custom scheme: each is served at the shared policy's `https` URL
 * under the reserved `.invalid` host, answered by its view's request handler. Throws when the bundle cannot be fetched (offline on first run), the platform is
 * unsupported, or CEF does not initialise; the runtime turns that into the cards' reason.
 */
internal class JcefAppStarter(private val config: JcefConfig) : BrowserStarter<CefApp> {
    override fun start(progress: BrowserProgress): CefApp {
        val builder = CefAppBuilder()
        builder.setInstallDir(config.installDir)
        builder.setProgressHandler { state, percent -> progress.report(stageOf(state), fractionOf(percent)) }
        builder.cefSettings.windowless_rendering_enabled = false
        builder.cefSettings.root_cache_path = config.cacheDir.absolutePath
        return builder.build()
    }

    companion object {
        private const val PERCENT = 100f

        fun stageOf(state: EnumProgress): String = state.name.lowercase()

        /** jcefmaven's percentage as 0..1, or null when it has no estimate. */
        fun fractionOf(percent: Float): Float? = percent.takeIf { it >= 0f }?.let { (it / PERCENT).coerceIn(0f, 1f) }
    }
}
