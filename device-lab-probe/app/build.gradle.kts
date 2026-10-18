plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "il.devicelab.probe"
    compileSdk = 35

    defaultConfig {
        applicationId = "il.devicelab.probe"
        minSdk = 33
        targetSdk = 33
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug { isMinifyEnabled = false }
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
