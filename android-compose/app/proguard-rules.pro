# Letta Mobile ProGuard Rules
# Last audited: letta-mobile-o7ob.2.3

# ---------------------------------------------------------------------------
# Data model serialization (kotlinx.serialization)
# Keep fields and constructors for all data models.
# ---------------------------------------------------------------------------
-keepclassmembers class com.letta.mobile.data.model.** {
    <fields>;
    <init>(...);
}

# ---------------------------------------------------------------------------
# Kotlin reflection metadata
# Required for kotlinx.serialization and Hilt.
# ---------------------------------------------------------------------------
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions
-keepattributes InnerClasses,EnclosingMethod

# ---------------------------------------------------------------------------
# Retrofit (interface method signatures must survive shrinker)
# ---------------------------------------------------------------------------
-dontwarn retrofit2.**
-keepclassmembernames,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# ---------------------------------------------------------------------------
# OkHttp — keep public API + TLS internals; let R8 shrink implementation.
# The blanket `keep class okhttp3.** { *; }` prevented R8 from eliminating
# dead OkHttp code (~300 kB of bytecode in practice).
# ---------------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.internal.publicsuffix.PublicSuffixDatabase { *; }
-keepnames class okhttp3.internal.** { *; }
-keepclassmembers class okhttp3.** {
    public protected *;
}
-keep interface okhttp3.** { *; }
-keep class okio.** { *; }

# ---------------------------------------------------------------------------
# Ktor — keep plugin/engine entrypoints; internal implementation is shrinkable.
# Previously `keep class io.ktor.** { *; }` kept all ~2MB of Ktor bytecode.
# ---------------------------------------------------------------------------
-dontwarn io.ktor.**
-keep class io.ktor.client.HttpClient { *; }
-keep class io.ktor.client.engine.** { *; }
-keep class io.ktor.client.plugins.** { *; }
-keep class io.ktor.serialization.** { *; }
# Ktor 2+ renamed HttpClientFeature → HttpClientPlugin; keep both shapes via dontwarn.
-dontwarn io.ktor.client.features.**
-keepclassmembers class * extends io.ktor.client.plugins.HttpClientPlugin { *; }

# ---------------------------------------------------------------------------
# Hilt / Dagger — generated component classes must survive renaming.
# ---------------------------------------------------------------------------
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * extends androidx.lifecycle.ViewModel { *; }

# ---------------------------------------------------------------------------
# Compose — AGP ships built-in Compose R8 rules since AGP 7.x.
# We only need to suppress dontwarn for internal APIs our own code touches.
# The old `keep class androidx.compose.** { *; }` blocked ALL Compose shrinking.
# ---------------------------------------------------------------------------
-dontwarn androidx.compose.**

# ---------------------------------------------------------------------------
# Coil 3 — keep loader factory and decoder registrations; let R8 shrink internals.
# ---------------------------------------------------------------------------
-dontwarn coil3.**
-keep class coil3.SingletonImageLoader$Factory { *; }
-keep class coil3.ImageLoader { *; }
-keep class coil3.decode.** { *; }
-keep class coil3.fetch.** { *; }
-keep class coil3.network.** { *; }

# ---------------------------------------------------------------------------
# ViewModels — keep all ViewModel subclasses (Hilt rule above handles @HiltViewModel;
# this catches any non-Hilt ViewModel that might be instantiated reflectively).
# ---------------------------------------------------------------------------
-keep class * extends androidx.lifecycle.ViewModel { *; }

# ---------------------------------------------------------------------------
# Enums
# ---------------------------------------------------------------------------
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---------------------------------------------------------------------------
# Navigation type-safe routes (kotlinx.serialization)
# ---------------------------------------------------------------------------
-keepclassmembers class com.letta.mobile.ui.navigation.** {
    <fields>;
    <init>(...);
}

# ---------------------------------------------------------------------------
# Netty (pulled in by Ktor server engine — suppress don't-warn only)
# ---------------------------------------------------------------------------
-dontwarn reactor.blockhound.**
-dontwarn io.netty.internal.tcnative.**
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.**
-dontwarn org.slf4j.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.aayushatharva.brotli4j.**
-dontwarn com.jcraft.jzlib.**
-dontwarn com.ning.compress.**
-dontwarn com.oracle.svm.core.**
-dontwarn lzma.sdk.**
-dontwarn net.jpountz.**
-dontwarn sun.security.ssl.**
-dontwarn jdk.jfr.**
-dontwarn java.lang.foreign.**

# ---------------------------------------------------------------------------
# Remove verbose logging in release builds.
# Log.w and Log.e are kept so warnings and errors still surface in crash tools.
# ---------------------------------------------------------------------------
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# ---------------------------------------------------------------------------
# Embedded LettaCode runtime — JNI native bridge (letta-mobile-esoaw).
# NativeLettaCodeNodeBridge has @JvmStatic companion callbacks
# (onNativeStdoutLine/onNativeStderrLine) invoked FROM native code via JNI,
# plus external native methods (nativeStart/nativeWriteStdin/nativeStop).
# R8 sees no Kotlin-side caller of the onNative* callbacks and strips/renames
# them, so the native lib's JNI lookup fails -> NoSuchMethodError -> SIGABRT
# on launch (release/minified only; debug works). DO NOT REMOVE these keeps —
# removing them crashes every production user on app open.
-keep class com.letta.mobile.runtime.local.NativeLettaCodeNodeBridge { *; }
-keepclassmembers class com.letta.mobile.runtime.local.NativeLettaCodeNodeBridge {
    public static *** onNativeStdoutLine(java.lang.String);
    public static *** onNativeStderrLine(java.lang.String);
}
# Keep any native method declarations in the runtime.local package (JNI side
# resolves these by name+signature too).
-keepclasseswithmembernames class com.letta.mobile.runtime.local.** {
    native <methods>;
}
