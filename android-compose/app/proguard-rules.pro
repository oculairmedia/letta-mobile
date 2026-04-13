# Letta Mobile ProGuard Rules

# Keep data classes for Retrofit/Moshi serialization
-keepclassmembers class com.letta.mobile.data.model.** {
    <fields>;
    <init>(...);
}

# Keep Kotlin metadata for reflection
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keepattributes *Annotation*

# Retrofit
-keepattributes Signature
-keepattributes Exceptions
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# Moshi
-keepclassmembers class ** {
    @com.squareup.moshi.Json <fields>;
}
-keep class com.squareup.moshi.** { *; }
-keepclassmembers @com.squareup.moshi.JsonClass class * {
    <init>(...);
}

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.lifecycle.HiltViewModel { *; }

# Compose
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Coil
-keep class coil.** { *; }
-dontwarn coil.**

# Keep ViewModels
-keep class * extends androidx.lifecycle.ViewModel { *; }

# Keep enums
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Navigation type-safe routes (kotlinx.serialization)
-keepclassmembers class com.letta.mobile.ui.navigation.** {
    <fields>;
    <init>(...);
}

# Netty (pulled in by Ktor server engine)
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

# Ktor
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
