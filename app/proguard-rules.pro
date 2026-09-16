# JNI entry points are resolved by name at runtime.
-keepclasseswithmembernames class * { native <methods>; }
-keep class to.eyed.inferno.engine.LlamaNative { *; }
