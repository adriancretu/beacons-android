# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in C:\dev\android-sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any project specific keep options here:

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

#Retrofit
-keep class retrofit2.** { *; }
-keepattributes Signature
-keepattributes Exceptions

-dontwarn okio.**
-dontwarn retrofit2.**

-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# Don't remove fields marked as GSON keys
-keepclassmembers class * {
  @com.google.gson.annotations.SerializedName <fields>;
}

#Curve25519 loads providers by class name...
-keep class * implements org.whispersystems.curve25519.Curve25519Provider