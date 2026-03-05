# Keep Xposed entry point
-keep class com.servicehook.HookMain { *; }

# Keep model for Gson
-keep class com.servicehook.model.** { *; }

# Keep ContentProvider
-keep class com.servicehook.StatsProvider { *; }

# Keep ProfileManager (encryption methods needed at runtime)
-keep class com.servicehook.ProfileManager { *; }

# Keep MainActivity public API (isModuleActive must survive)
-keep class com.servicehook.MainActivity {
    public static boolean isModuleActive();
}

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
