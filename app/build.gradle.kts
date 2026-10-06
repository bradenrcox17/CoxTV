import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Fire TV app. Version lives in /version.properties (bumped by scripts/release.ps1).
val versionProps = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }

// Release signing comes from environment variables set by scripts/release.ps1, so the
// keystore and its passwords never touch the repo. Without them, release builds fall back
// to the debug key (fine for local testing, but such builds can't update a released install).
val releaseKeystore: String? = System.getenv("COXTV_KEYSTORE")

android {
    namespace = "com.coxtv"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.coxtv"
        minSdk = 25
        targetSdk = 36
        versionCode = versionProps.getProperty("versionCode").toInt()
        versionName = versionProps.getProperty("versionName")
        buildConfigField("String", "GITHUB_REPO", "\"${providers.gradleProperty("coxtv.githubRepo").get()}\"")
        buildConfigField("String", "APK_ASSET", "\"CoxTV.apk\"")
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
    implementation(libs.tv.material)

    implementation(libs.media3.ui)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
}
