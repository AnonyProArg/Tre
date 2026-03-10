plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val libboxAar = file("libs/libbox.aar")

android {
    namespace = "com.orotoloco.tunsbox"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.orotoloco.tunsbox"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        buildConfigField("boolean", "HAS_LIBBOX_AAR", libboxAar.exists().toString())
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    if (libboxAar.exists()) {
        implementation(files(libboxAar))
    }
}
