# proguard-rules.pro

# ---- Kotlin serialization ----
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.ping.app.**$$serializer { *; }
-keepclassmembers class com.ping.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.ping.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---- Bouncy Castle ----
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
-dontwarn org.slf4j.**

# ---- Room ----
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# ---- OkHttp ----
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.openjsse.**

# ---- SQLCipher ----
-keep class net.zetetic.database.** { *; }
-dontwarn net.zetetic.database.**

# ---- ZXing ----
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# ---- Coil ----
-dontwarn coil.**

# ---- Coroutines ----
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# ---- Model classes touched by reflection-free serialization ----
-keep class com.ping.app.core.crypto.** { *; }
-keep class com.ping.app.core.transport.** { *; }
