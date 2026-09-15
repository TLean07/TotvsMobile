# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# Keep everything Gson/Retrofit reads by reflection.
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*, SourceFile, LineNumberTable
-keep class com.tlean07.totvs.network.dto.** { *; }
-keep class com.tlean07.totvs.network.PendingQueue$Item { *; }
-keep class com.tlean07.totvs.audio.store.Recording { *; }
-keep class com.tlean07.totvs.audio.store.Recording$* { *; }
-keep class com.tlean07.totvs.security.AuditLog$Entry { *; }
-keep class com.tlean07.totvs.cas.DeviceInfo { *; }
-keep class com.tlean07.totvs.network.ApiService { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Vosk / JNA (native bridge resolves classes by name)
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-dontwarn com.sun.jna.**
-dontwarn java.awt.**

# Lombok-generated DTOs of the local LeanIA (built with builders; keep for Gson-free safety)
-keep class com.tlean07.totvs.leania.** { *; }

# WorkManager workers are instantiated by name
-keep class * extends androidx.work.Worker { public <init>(...); }

-dontwarn org.slf4j.**
-dontwarn javax.annotation.**

# Room / WorkManager: generated database impls and workers are looked up reflectively.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class androidx.work.impl.WorkDatabase_Impl { *; }
-keep class * extends androidx.work.ListenableWorker { public <init>(...); }
-keep class * implements androidx.startup.Initializer { public <init>(); }
-keep class androidx.work.WorkManagerInitializer { *; }
