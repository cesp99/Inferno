-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# JNI: entry points are resolved by name; native reads these classes' fields via GetFieldID.
-keepclasseswithmembernames class * { native <methods>; }
-keep class to.eyed.inferno.engine.LlamaNative { *; }
-keep class to.eyed.inferno.engine.NativeChatMessage { *; }
-keep class to.eyed.inferno.engine.NativeImage { *; }
-keep class to.eyed.inferno.engine.ProgressCallback { *; }

# kotlinx.serialization
-keepclassmembers @kotlinx.serialization.Serializable class to.eyed.inferno.** { static **$* *; }
-keepclassmembers class to.eyed.inferno.**$$serializer { *** INSTANCE; }
