# Add project specific ProGuard rules here.
# Keep OkHttp classes
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# Keep JSON parsing
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
