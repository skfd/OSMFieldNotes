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
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Timber
-dontwarn org.jetbrains.annotations.**

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Navigation Component
-keepnames class * extends android.os.Parcelable
-keepnames class * extends java.io.Serializable
# Note comments and labels are stored as Gson JSON in Room; field and enum names must survive R8.
-keep class com.surveyme.data.model.** { *; }

# Tink (pulled in by security-crypto) references errorprone annotations that are not on the classpath.
-dontwarn com.google.errorprone.annotations.**

# ---- Release prep (roadmap item 16) -------------------------------------------------------
# The release build is minified; these rules are what keeps it working. Verified with
# assembleRelease (R8 full mode). Gson, Room, OkHttp and osmdroid ship their own consumer
# rules; the lines below cover what those do not.

# Gson: generic signatures for TypeToken<List<NoteComment>> etc. in Converters, and the
# annotation-driven field names. Gson consumer rules cover TypeToken itself.
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Parcelize / Serializable payloads that travel through Bundles and navigation args.
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Tink (behind security-crypto) references optional annotation and JDK classes at compile
# time only; its protobuf keep rules ship inside the library, so no blanket keep here
# (a keep-all on tink costs 450 KB of APK).
-dontwarn com.google.crypto.tink.**
-dontwarn javax.annotation.**

# OkHttp / okio reference Conscrypt, BouncyCastle and OpenJSSE that are not on the classpath.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# osmdroid is used through its public API only; silence its optional dependencies.
-dontwarn org.osmdroid.**

# Kotlin coroutines: ServiceLoader-based Android dispatcher and the debug probes.
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.android.AndroidDispatcherFactory { *; }
-keep class kotlinx.coroutines.android.AndroidExceptionPreHandler { *; }

# The database class name shows up in log lines and the backup helper; keep it readable.
-keep class com.surveyme.data.database.SurveyMeDatabase { *; }

# Stack traces in crash_log.txt stay readable only with the mapping.txt of that build;
# archive app/build/outputs/mapping/release/mapping.txt next to each APK (see RELEASE.md).
-renamesourcefileattribute SourceFile
