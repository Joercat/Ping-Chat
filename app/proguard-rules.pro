# R8 rules for Ping. The release build does not minify (see app/build.gradle.kts), so these
# mostly matter if R8 is ever switched on — but the keeps below are the ones that would bite.

# ---- Bouncy Castle ---------------------------------------------------------------------
# Loaded reflectively at runtime (org.bouncycastle.jce.provider.BouncyCastleProvider) and used
# through the JCE, so the provider and its algorithm implementations must survive shrinking.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**

# ---- SQLCipher -------------------------------------------------------------------------
# The native library is loaded by the framework class itself; the loader must not be renamed.
-keep class net.zetetic.database.** { *; }
-keep class net.zetetic.database.sqlcipher.SQLiteDatabase { *; }
-dontwarn net.zetetic.**

# ---- QR scanning (zxing-android-embedded) ----------------------------------------------
-keep class com.journeyapps.barcodescanner.** { *; }
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# ---- AndroidX / Compose internals that reflectively look things up ----------------------
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-keep class androidx.lifecycle.DefaultLifecycleObserver
-keep class androidx.core.app.NotificationCompat { *; }

# ---- The app's own reflection-free-but-enumerated types -------------------------------
# Kept so that a future minified build cannot break the state machine by stripping enums or
# companion objects that are only referenced from debug tooling.
-keepclassmembers enum com.ping.app.** { *; }
-keepclassmembers class com.ping.app.** {
    *** Companion;
}
