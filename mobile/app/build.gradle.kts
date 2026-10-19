import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.folio.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.folio.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        create("release") {
            // Credentials live in keystore.properties (git-ignored). Fall back to
            // the debug keystore so `assembleRelease` still works without it.
            val props = Properties()
            val propsFile = rootProject.file("keystore.properties")
            if (propsFile.exists()) {
                propsFile.inputStream().use { props.load(it) }
            }
            val storeFile = props.getProperty("storeFile")
            if (storeFile != null) {
                this.storeFile = rootProject.file(storeFile)
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            } else {
                // No keystore.properties: sign with the debug keystore so
                // `assembleRelease` still produces an installable APK.
                val debugKeystore = File(System.getProperty("user.home"), ".android/debug.keystore")
                if (debugKeystore.exists()) {
                    this.storeFile = debugKeystore
                    storePassword = "android"
                    keyAlias = "androiddebugkey"
                    keyPassword = "android"
                }
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    kotlin {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        }
    }

    // PDFBox-Android logs through android.util.Log; JVM unit tests have no
    // Android runtime, so stubbed calls return defaults instead of throwing.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // The Google client jars each ship a META-INF/DEPENDENCIES marker, which
    // would otherwise collide when the jars are merged into the APK.
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/LICENSE",
                "META-INF/NOTICE",
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // Compose BOM: all compose artifacts below follow the BOM version
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jsoup)
    implementation(libs.pdfbox.android)
    implementation(libs.readium.shared)
    implementation(libs.readium.streamer)
    implementation(libs.readium.navigator)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.activity)
    // Opt-in background cloud sync (WorkManager).
    implementation(libs.androidx.work.runtime.ktx)

    // Cloud sync (Google Drive appDataFolder). The Drive service client is the
    // only network dependency in the app; everything else stays local.
    implementation(libs.google.api.client.android)
    implementation(libs.google.http.client.gson)
    // The Drive client pulls the Apache transport in by default; Folio uses
    // the JDK transport (NetHttpTransport), so the Apache stack is dropped
    // rather than shipped unused.
    implementation(libs.google.api.services.drive) {
        exclude(group = "com.google.http-client", module = "google-http-client-apache-v2")
    }
    // GoogleAuthUtil / GoogleAuthException, used by GoogleAccountCredential to
    // obtain Drive tokens through the system account manager.
    implementation(libs.play.services.auth)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)

    debugImplementation(libs.androidx.ui.tooling)
}
