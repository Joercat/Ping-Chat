// app/build.gradle.kts
import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Sideload signing key, committed on purpose.
//
// Both the debug and the release build are signed with this one key so that a new build installs
// *over* an older one. Android refuses to update an app whose signature changed — the installer
// says "App not installed" and the only way forward is to uninstall first, losing all local data
// (and this app keeps everything local). CI runners generate a fresh ~/.android/debug.keystore on
// every run, so without a committed key, every build was a different app.
//
// This key is public and is not a secret: it proves only "built by Ping's own pipeline". It is not
// a security boundary, and nothing in the app's cryptography depends on it. Anyone who wants to
// sign their own builds can still override it with keystore.properties (see docs/BUILD.md) or the
// KEYSTORE_* environment variables.
val sideloadKeystoreFile = rootProject.file("app/keystore/ping.p12")
val sideloadKeystorePassword = "ping-sideload"
val sideloadKeyAlias = "ping"

// Optional release signing. Set up keystore.properties (see docs/BUILD.md) or
// the KEYSTORE_* environment variables. Without them the release build is
// signed with the key above so that it stays installable for personal use.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    // A fully qualified `java.io.FileInputStream` does not work in a Kotlin DSL script: `java`
    // resolves to the Java plugin extension, not the package, so the import above is required.
    if (keystorePropertiesFile.exists()) load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.ping.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ping.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "2.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        // The debug variant uses the committed key too, so a debug APK can also be updated in
        // place. It is still a separate application id-free build: nothing else changes.
        if (sideloadKeystoreFile.exists()) {
            getByName("debug") {
                storeFile = sideloadKeystoreFile
                storePassword = sideloadKeystorePassword
                keyAlias = sideloadKeyAlias
                keyPassword = sideloadKeystorePassword
            }
        }
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = keystoreProperties.getProperty("storeFile")?.let { file(it) }
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            } else {
                val path = System.getenv("KEYSTORE_PATH")
                if (!path.isNullOrEmpty() && file(path).exists()) {
                    storeFile = file(path)
                    storePassword = System.getenv("KEYSTORE_PASSWORD")
                    keyAlias = System.getenv("KEY_ALIAS")
                    keyPassword = System.getenv("KEY_PASSWORD")
                } else if (sideloadKeystoreFile.exists()) {
                    storeFile = sideloadKeystoreFile
                    storePassword = sideloadKeystorePassword
                    keyAlias = sideloadKeyAlias
                    keyPassword = sideloadKeystorePassword
                }
            }
        }
    }

    buildTypes {
        release {
            // R8 with a hand-written rule set is a needle in the foot for a build that
            // cannot be smoke-tested here; the APK ships unobfuscated on purpose.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val releaseConfig = signingConfigs.findByName("release")
            signingConfig = if (releaseConfig?.storeFile?.exists() == true) {
                releaseConfig
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/license.txt"
            excludes += "/META-INF/NOTICE"
            excludes += "/META-INF/NOTICE.txt"
            excludes += "/META-INF/notice.txt"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    lint {
        abortOnError = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // AndroidX core + lifecycle
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.fragment:fragment-ktx:1.8.2")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")

    // Storage: SQLCipher is the only native dependency, and it is the one that
    // encrypts the whole database file on top of the per-column sealing.
    implementation("net.zetetic:sqlcipher-android:4.6.1")
    implementation("androidx.sqlite:sqlite:2.4.0")

    // Network (relay + local peer-to-peer sockets)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Crypto (BouncyCastle provides X25519/Ed25519 on older devices)
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // QR codes: generation for sharing, scanning for adding contacts
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // Optional app lock
    implementation("androidx.biometric:biometric:1.1.0")

    // Tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
