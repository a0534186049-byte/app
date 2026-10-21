plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "il.devicelab"
    compileSdk = 35

    defaultConfig {
        applicationId = "il.devicelab"
        minSdk = 33
        targetSdk = 35
        versionCode = 3
        versionName = "2.0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
