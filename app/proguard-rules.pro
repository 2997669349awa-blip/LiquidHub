# AVNC (com.github.tiny-computer:avnc) ships no consumer ProGuard rules, so the
# workspace-desktop VNC viewer needs explicit keeps for R8.
-keep class com.gaurav.avnc.** { *; }
-keepclassmembers class com.gaurav.avnc.** { *; }
-dontwarn com.gaurav.avnc.**

# AVNC uses Android Data Binding at runtime.
-keep class androidx.databinding.** { *; }
-keep class * extends androidx.databinding.ViewDataBinding { *; }
-keepclassmembers class * extends androidx.databinding.ViewDataBinding {
    <init>(...);
}
-dontwarn androidx.databinding.**

# AVNC keeps its server profiles in Room.
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-dontwarn androidx.room.**

# VNC over SSH support library.
-keep class org.connectbot.** { *; }
-dontwarn org.connectbot.**

# kotlinx.serialization models (ServerProfile etc.) resolved by reflection.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.gaurav.avnc.**$$serializer { *; }
