plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

// Shared by the TV app (:app) and the phone app (:mobile): playlist/guide parsing, Room,
// settings, favorites, search, the player setup and the GitHub update checker.
android {
    namespace = "com.coxtv.core"
    compileSdk = 37

    defaultConfig {
        minSdk = 25
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    api(libs.androidx.core.ktx)

    api(libs.media3.exoplayer)
    api(libs.media3.exoplayer.hls)
    api(libs.media3.datasource.okhttp)

    api(libs.room.runtime)
    api(libs.room.ktx)
    ksp(libs.room.compiler)

    api(libs.work.runtime.ktx)
    api(libs.datastore.preferences)
    api(libs.okhttp)
}
