import java.io.File
import java.util.Properties

// ---------------------------------------------------------------------------
// Release signing policy (S1 audit P0 — production builds must never silently
// sign with the debug key).
//
// - Real credentials live in git-ignored `mobile/keystore.properties`
//   (storeFile/storePassword/keyAlias/keyPassword). CI/Play builds inject
//   these from secrets.
// - Without them, ANY release-variant task FAILS with a clear error.
// - An explicitly requested fallback (gradle property
//   `folio.localReleaseSigning` or env `FOLIO_LOCAL_RELEASE_SIGNING`) signs
//   with the debug keystore so an installable APK still ships for
//   user-testing. That artifact is NOT for publication — ever.
val releaseProps = Properties()
val keystorePropsFile = rootProject.file("keystore.properties")
if (keystorePropsFile.exists()) {
    keystorePropsFile.inputStream().use { releaseProps.load(it) }
}
val hasProductionSigning = !releaseProps.getProperty("storeFile").isNullOrBlank()
val explicitLocalReleaseSigning =
    providers.gradleProperty("folio.localReleaseSigning").isPresent ||
        providers.environmentVariable("FOLIO_LOCAL_RELEASE_SIGNING").isPresent

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
        versionCode = 2
        versionName = "0.2.0"
    }

    signingConfigs {
        create("release") {
            if (hasProductionSigning) {
                this.storeFile = rootProject.file(releaseProps.getProperty("storeFile"))
                storePassword = releaseProps.getProperty("storePassword")
                keyAlias = releaseProps.getProperty("keyAlias")
                keyPassword = releaseProps.getProperty("keyPassword")
            } else if (explicitLocalReleaseSigning) {
                val debugKeystore = File(System.getProperty("user.home"), ".android/debug.keystore")
                if (!debugKeystore.exists()) {
                    throw GradleException(
                        "folio.localReleaseSigning was requested, but no debug keystore " +
                                "exists at ${debugKeystore.absolutePath} to sign with."
                    )
                }
                this.storeFile = debugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
            // Neither case: stay unset — the taskGraph gate below fails any
            // release task with an explanatory error instead of producing an
            // unsigned/target-keystore APK silently.
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

    // (The Google Drive client jars that needed META-INF/DEPENDENCIES,
    // INDEX.LIST etc. excluded were removed with Drive sync — S1 audit
    // Phase 1. If a future dependency reintroduces a META-INF collision,
    // re-add only the specific markers the clean build reports.)
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

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)

    debugImplementation(libs.androidx.ui.tooling)
}

// ---------------------------------------------------------------------------
// Release signing gate: every release-variant task depends on this gate first,
// so no release build can run without real credentials or an explicit local
// fallback (the debug-key path for user-testing builds only).
val folioReleaseSignGate = tasks.register("folio-release-sign-gate") {
    doFirst {
        when {
            hasProductionSigning -> {
                println("[FOLIO] Release tasks signing with keystore.properties (${releaseProps.getProperty("keyAlias") ?: "release"}).")
            }
            explicitLocalReleaseSigning -> {
                println(
                    """
                    ============================================================
                    [FOLIO] WARNING: release tasks are being signed with the
                    Android DEBUG keystore (folio.localReleaseSigning).
                    Installable for device testing ONLY. NEVER upload this
                    artifact to Play or distribute it.
                    ============================================================
                    """.trimIndent()
                )
            }
            else -> throw GradleException(
                """
                [FOLIO] Release signing is not configured.

                Release-variant tasks (e.g. :app:assembleRelease) refuse to run so
                no build can ever go out signed with the debug keystore by mistake.

                To build a production release, create the git-ignored
                mobile/keystore.properties with storeFile, storePassword,
                keyAlias and keyPassword.

                To build an installable local/user-test APK (signed with the
                DEBUG keystore — absolutely not for publication):
                  ./gradlew -Pfolio.localReleaseSigning=true :app:assembleRelease
                  (or env FOLIO_LOCAL_RELEASE_SIGNING=1)
                """.trimIndent()
            )
        }
    }
}

tasks.matching { task ->
    task.project.path == ":app" && task.name.contains("Release")
}.configureEach {
    dependsOn(folioReleaseSignGate)
}
