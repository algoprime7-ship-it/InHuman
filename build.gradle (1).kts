plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ai.inhuman.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.inhuman.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // Same key on every GitHub build, so new versions install over old ones.
    // The workflow turns inhuman.keystore.b64 into inhuman.keystore before building.
    signingConfigs {
        create("inhuman") {
            storeFile = file("inhuman.keystore")
            storePassword = "inhuman-ai"
            keyAlias = "inhuman"
            keyPassword = "inhuman-ai"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("inhuman")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    // "Jarvis" wake word engine (needs a free AccessKey from console.picovoice.ai)
    implementation("ai.picovoice:porcupine-android:3.0.+")
}
