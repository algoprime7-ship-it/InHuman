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
        versionCode = 10
        versionName = "10.0"
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
    // Offline trigger phrase engine ("hey jarvis", "hey parvis", any phrase)
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    // FileProvider (sharing voice notes with WhatsApp)
    implementation("androidx.core:core:1.13.1")
}
