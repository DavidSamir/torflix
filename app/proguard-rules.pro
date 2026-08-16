# TORFILX release rules.
#
# R8 full mode is on. Everything below is a rule R8 cannot infer on its own: native bindings,
# reflective serialization, and Room/Hilt generated code.

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
