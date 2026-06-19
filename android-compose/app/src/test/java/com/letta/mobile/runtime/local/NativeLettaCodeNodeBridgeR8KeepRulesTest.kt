package com.letta.mobile.runtime.local

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeLettaCodeNodeBridgeR8KeepRulesTest {
    @Test
    fun proguardRulesKeepJniBridgeCallbacksAndNativeMethods() {
        val rules = File("proguard-rules.pro").readText()

        assertTrue(
            rules.contains("-keep class com.letta.mobile.runtime.local.NativeLettaCodeNodeBridge { *; }")
        )
        assertTrue(
            rules.contains("-keepclassmembers class com.letta.mobile.runtime.local.NativeLettaCodeNodeBridge") &&
                rules.contains("public static *** onNativeStdoutLine(java.lang.String);") &&
                rules.contains("public static *** onNativeStderrLine(java.lang.String);")
        )
        assertTrue(
            rules.contains("-keepclasseswithmembernames class com.letta.mobile.runtime.local.**") &&
                rules.contains("native <methods>;")
        )
    }
}
