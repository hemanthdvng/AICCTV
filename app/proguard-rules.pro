# ── TensorFlow Lite ──────────────────────────────
-keep class org.tensorflow.** { *; }
-keepclassmembers class org.tensorflow.** { *; }
-dontwarn org.tensorflow.**

# ── LiteRT (litertlm) ────────────────────────────
-keep class com.google.ai.edge.** { *; }
-keepclassmembers class com.google.ai.edge.** { *; }
-dontwarn com.google.ai.edge.**

# ── ML Kit ───────────────────────────────────────
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
-keep class com.google.android.gms.vision.** { *; }

# ── WebRTC ───────────────────────────────────────
-keep class org.webrtc.** { *; }
-keepclassmembers class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# ── Hilt / Dagger ────────────────────────────────
-keep class dagger.hilt.** { *; }
-keepclassmembers class * {
    @dagger.hilt.android.lifecycle.HiltViewModel <init>(...);
}
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }
-keep @javax.inject.Singleton class * { *; }
-keepclassmembers class * { @javax.inject.Inject *; }

# ── Room ─────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }

# ── Firebase ─────────────────────────────────────
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# ── NanoHTTPD ────────────────────────────────────
-keep class fi.iki.elonen.** { *; }
-keep class org.nanohttpd.** { *; }

# ── Gson ─────────────────────────────────────────
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keep class com.securecam.** { *; }

# ── Kotlin coroutines ────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

# ── ExoPlayer / Media3 ───────────────────────────
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ── General ──────────────────────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver