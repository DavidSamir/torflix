# TORFILX release rules.
#
# NOTE: R8/minification is currently DISABLED for release (isMinifyEnabled = false in
# app/build.gradle.kts), because ART on Fire OS 5 miscompiles R8's optimised dex and crashes with a
# native SIGSEGV at launch. These rules therefore DO NOT run today. They are kept, validated and
# ready so that minification can be turned back on — e.g. for a build whose minSdk is raised above
# Fire OS 5 — without having to rediscover the native/reflection keeps below.

# --- libtorrent4j -------------------------------------------------------------------------------
# The native library calls back into these classes by name through SWIG/JNI; renaming or removing
# them turns into an UnsatisfiedLinkError at the first torrent, not a build error.
-keep class org.libtorrent4j.** { *; }
-keep class org.libtorrent4j.swig.** { *; }
-dontwarn org.libtorrent4j.**

# --- kotlinx.serialization ------------------------------------------------------------------------
# The catalogue is parsed reflectively via generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.torfilx.**$$serializer { *; }
-keepclassmembers class com.torfilx.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Room ------------------------------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# --- Media3 ----------------------------------------------------------------------------------------
# Renderers and extractors are instantiated reflectively by name.
-keep class androidx.media3.exoplayer.** { *; }
-dontwarn androidx.media3.**

# --- Coroutines / Compose --------------------------------------------------------------------------
-dontwarn kotlinx.coroutines.**
-dontwarn org.jetbrains.annotations.**

# Keep line numbers so a stack trace pulled off a Fire Stick is readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
