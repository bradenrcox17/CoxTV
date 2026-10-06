import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Phone/tablet app. Separate application ID so it installs alongside the TV app; same version
// (/version.properties) and same release keystore (env vars from scripts/release.ps1).
val versionProps = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }
val releaseKeystore: String? = System.getenv("COXTV_KEYSTORE")

android {
    namespace = "com.coxtv.mobile"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.coxtv.mobile"
        minSdk = 25
        targetSdk = 36
        versionCode = versionProps.getProperty("versionCode").toInt()
        versionName = versionProps.getProperty("versionName")
        buildConfigField("String", "GITHUB_REPO", "\"${providers.gradleProperty("coxtv.githubRepo").get()}\"")
        buildConfigField("String", "APK_ASSET", "\"CoxTV-mobile.apk\"")
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("COXTV_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("COXTV_KEY_ALIAS")
                keyPassword = System.getenv("COXTV_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)

    implementation(libs.media3.ui)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
}
