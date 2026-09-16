# JNI: entry points are resolved by name. libinferno_sd's JNI_OnLoad also does
# FindClass("to/eyed/inferno/sd/SdNative$Listener") + GetMethodID("onProgress"/"onPreview"), so the nested
# interface and its method names must survive R8 (full mode renames/merges interfaces nobody references by name).
# Implementers (ImageEngine.GenListener) keep their overriding method names automatically once the interface is kept.
-keepclasseswithmembernames class * { native <methods>; }
-keep class to.eyed.inferno.sd.SdNative { *; }
-keep class to.eyed.inferno.sd.SdNative$Listener { *; }
-keepattributes InnerClasses,EnclosingMethod
