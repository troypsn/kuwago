# Add project specific ProGuard rules here.

# --------------------------------------------------------------------------
# 1. Strip Android Log calls in Release builds (Fixes MobSF CWE-532 Info #1)
# --------------------------------------------------------------------------
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

# --------------------------------------------------------------------------
# 2. Keep Retrofit / Gson / OkHttp Networking Models & Interfaces
# --------------------------------------------------------------------------
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes *Annotation*

-keep class com.example.kuwago.network.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Prevent OkHttp / Retrofit platform warnings
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**

# --------------------------------------------------------------------------
# 3. Keep Room Database Entities, DAOs, and App Data Models
# --------------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class com.example.kuwago.db.** { *; }
-keep class com.example.kuwago.DetectionResult { *; }
-keep class com.example.kuwago.Classification { *; }
-keep class com.example.kuwago.BlacklistEntry { *; }
-keep class com.example.kuwago.PendingWarning { *; }
-keep class com.example.kuwago.InstalledAppInfo { *; }

-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# --------------------------------------------------------------------------
# 4. Keep JNI Native Methods
# --------------------------------------------------------------------------
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# --------------------------------------------------------------------------
# 5. ONNX Runtime — Keep JNI classes while allowing OrtUtil to be obfuscated/inlined
# --------------------------------------------------------------------------
-keep class !ai.onnxruntime.OrtUtil, ai.onnxruntime.** { *; }

# --------------------------------------------------------------------------
# 6. SQLCipher — Keep JNI & Room entrypoints while shrinking unused DatabaseUtils
# --------------------------------------------------------------------------
-keep class !net.sqlcipher.DatabaseUtils, net.sqlcipher.database.** { *; }
-keep class net.sqlcipher.Cursor* { *; }
-keep class net.sqlcipher.CustomCursorWindowAllocation { *; }
-keep class net.sqlcipher.SQLException { *; }
-keep class net.sqlcipher.Abstract* { *; }
-keep class net.sqlcipher.BulkCursor* { *; }
-keep class net.sqlcipher.CrossProcessCursorWrapper { *; }
-keep class net.sqlcipher.CursorWrapper { *; }
-keep class net.sqlcipher.MatrixCursor* { *; }
-keep class net.sqlcipher.StaleDataException { *; }
