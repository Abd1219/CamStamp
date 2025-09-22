# CamStamp ProGuard Rules - Simplified for stability

# Keep line numbers for debugging
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep all application classes (no obfuscation for main classes)
-keep class com.abdapps.camstamp.** { *; }

# Keep all activities, services, and receivers
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# CameraX
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# Compose
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Location services
-keep class com.google.android.gms.location.** { *; }
-dontwarn com.google.android.gms.**

# EXIF interface
-keep class androidx.exifinterface.** { *; }

# Remove logging in release builds only
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
}