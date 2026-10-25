# The engine dex is compiled against these class names and links to them by name at runtime.
# Renaming or removing them would break the DexClassLoader handshake with a NoClassDefFoundError.
-keep class com.frenetic.litechunk.api.** { *; }
-keep interface com.frenetic.litechunk.api.** { *; }

# Keep the line numbers in crash reports, the isolated process reports failures back as text.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Chunker is loaded from assets, the app itself never references it.
-dontwarn com.hivemc.chunker.**
-dontwarn org.iq80.**
