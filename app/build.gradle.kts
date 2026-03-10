plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val arm64Binary = file("src/main/assets/sing-box/arm64-v8a/sing-box")
val x64Binary = file("src/main/assets/sing-box/x86_64/sing-box")

android {
    namespace = "com.orotoloco.tunsbox"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.orotoloco.tunsbox"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "0.3.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        buildConfigField(
            "boolean",
            "HAS_SINGBOX_BINARIES",
            (arm64Binary.exists() && x64Binary.exists()).toString()
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
