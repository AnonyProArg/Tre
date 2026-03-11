plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val singBoxArchive = rootProject.file("sing-box-1.13.2-android-arm64.tar.gz")
val generatedJniLibsDir = layout.buildDirectory.dir("generated/singbox/jniLibs")

val prepareSingBoxJniLibs by tasks.registering(Copy::class) {
    from(tarTree(resources.gzip(singBoxArchive))) {
        include("sing-box-1.13.2-android-arm64/sing-box")
        eachFile { path = "arm64-v8a/libsingbox.so" }
        includeEmptyDirs = false
    }
    into(generatedJniLibsDir)
}

android {
    namespace = "com.example.localvpn"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.localvpn"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
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

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(generatedJniLibsDir.get().asFile)
        }
    }

    packagingOptions {
        jniLibs {
            useLegacyPackaging = true
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

tasks.named("preBuild") {
    dependsOn(prepareSingBoxJniLibs)
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
