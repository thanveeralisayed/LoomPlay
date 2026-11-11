# LoomPlay ProGuard / R8 rules

# Preserve libmpv JNI callbacks and native entrypoints
-keep class dev.jdtech.mpv.** { *; }
-dontwarn dev.jdtech.mpv.**

# Keep LoomPlay serializable models & Navigation Compose routes
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers class * {
    @kotlinx.serialization.* <fields>;
    @kotlinx.serialization.* <methods>;
}
-keep @kotlinx.serialization.Serializable class com.loomplay.** {
    *;
}
-keepclassmembers class com.loomplay.app.*Route* {
    *;
}

# Preserve readable stack traces
-keepattributes SourceFile,LineNumberTable
