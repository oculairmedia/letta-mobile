package com.letta.mobile.desktop.hidpi

import dev.nucleusframework.hidpi.applyLinuxHiDpiScale
import java.lang.Runnable

/** Configures the AWT rendering and input scale before Compose or Skia initializes. */
internal class LinuxAwtScale : Runnable {
    override fun run() {
        if (!System.getProperty("os.name").contains("Linux", ignoreCase = true)) return
        applyLinuxHiDpiScale()
        // Compose enables Skiko's Xft-only autodetection by default. On XWayland this
        // overwrites the explicit/native scale with 1, before AWT reads it. Keep the
        // scale Nucleus selected so rendering and pointer coordinates use the same transform.
        if (System.getProperty("sun.java2d.uiScale") != null) {
            System.setProperty("skiko.linux.autodpi", "false")
        }
    }
}
