plugins {
    id("org.jetbrains.kotlinx.kover") version "0.9.8"
    id("com.android.application") version "9.2.0" apply false
    id("com.android.library") version "9.2.0" apply false
    id("com.android.kotlin.multiplatform.library") version "9.2.0" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.0" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.4.0" apply false
    // Pinned to 1.10.0 to match Jewel 0.37.0-262.4852.51, which is built against
    // Compose foundation 1.10.0. Compose 1.11.x changed the return type of
    // TextContextMenu.TextManager.getCut() (Function0 -> Action), which makes
    // Jewel's precompiled call unresolvable -> NoSuchMethodError at runtime when a
    // text-field context menu composes (letta-mobile-5icsp).
    id("org.jetbrains.compose") version "1.10.0" apply false
    id("app.cash.paparazzi") version "2.0.0-alpha05" apply false
    id("io.github.takahirom.roborazzi") version "1.63.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.0" apply false
    id("com.google.dagger.hilt.android") version "2.59.2" apply false
    id("com.google.devtools.ksp") version "2.3.9" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
