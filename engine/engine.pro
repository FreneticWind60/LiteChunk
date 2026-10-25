-dontobfuscate
-dontoptimize
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable

# App facing entry points (EngineImpl) and the on-device diagnostic CLI (EngineCli).
-keep class com.frenetic.litechunk.engine.** { *; }

# Chunker is kept whole on purpose: it dispatches through deep interface hierarchies and reflects over
# ChunkerLevelSettings fields, so shrinking it risks silent conversion bugs while saving very little
# (the bulk of the code is block/biome data tables that are reachable from the encoders anyway).
-keep class com.hivemc.chunker.** { *; }
-keep class org.iq80.leveldb.** { *; }
-keep class net.jpountz.** { *; }

# Gson reflection targets.
-keepclassmembers class * { @com.google.gson.annotations.SerializedName <fields>; }
-keepclassmembers enum * { *; }

# Guava's TypeToken reads its own generic superclass via getGenericSuperclass(). R8 merges anonymous
# subclasses and drops their Signature attribute, which makes TypeToken throw
# "class ... isn't parameterized" the first time ChunkerItemProperty is initialised on device.
# Keeping these classes as roots forces R8 to preserve the parameterised superclass signature.
-keep class * extends com.google.common.reflect.TypeToken { <init>(); }
-keepattributes Signature

# Desktop JVM APIs that Chunker references but never reaches on Android.
-dontwarn **
