plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.mythara"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mythara"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Ship only the architecture we actually run on. Pixels are arm64
        // exclusively; bundling x86 + armv7a would more-than-double the
        // APK because of Vosk's native .so + JNA. Sideload audience here
        // is one device family; if we ever publish broadly, add the rest.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }

    sourceSets {
        getByName("main") {
            kotlin.srcDir("src/main/kotlin")
        }
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.splashscreen)

    // Compose
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Coroutines + Serialization
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Persistence
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Network (MiniMax client)
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.okhttp.logging)

    // Crypto — Tink AEAD for at-rest secrets (M2 API key encryption)
    implementation(libs.tink.android)

    // WorkManager — GrowthScheduler nightly + weekly self-learning jobs (M8.0+)
    implementation(libs.androidx.work.runtime)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Device-credential / biometric unlock at app entry
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.lifecycle.process)

    // Vosk on-device ASR for Observe mode (M8.1b). Self-contained — model
    // ships as a lazy-downloaded zip, not bundled in the APK. Both deps
    // use the @aar qualifier per Vosk's own demo build to:
    //   1. select the Android-variant native binaries (vs the desktop jar)
    //   2. disable transitive resolution that otherwise drags JNA's jar
    //      AND aar variants into the same module → duplicate-class crash
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.47@aar")

    // MediaPipe Text Embedder — on-device 100-dim sentence embeddings via
    // Universal Sentence Encoder Lite. Lazy-downloaded ~6MB tflite model.
    implementation(libs.mediapipe.tasks.text)

    // LiteRT-LM — on-device Gemma 4 E2B for M8.2.2 fact extraction. Replaces
    // MediaPipe Tasks-GenAI as Google's supported runtime path. Speaks the
    // `.litertlm` bundle format, auto-dispatches to GPU/NPU on supported
    // SoCs (Tensor G3/G4, Snapdragon 8 Elite), falls back to CPU elsewhere.
    // Model (~2.6GB Apache-2.0) is lazy-downloaded into filesDir.
    implementation(libs.litertlm.android)

    // ML Kit Language Identification — BCP-47 tag detection. Used to
    // pick the right TTS Locale for the assistant's reply and to add
    // language facets to Observe vault records.
    implementation(libs.google.mlkit.language.id)

    // openWakeWord (Re-MENTIA Kotlin wrapper) — on-device wake-word
    // detection via ONNX Runtime. Drives M8.3a's "Hey Jarvis" trigger
    // (user-facing identity stays Lumi). All three ONNX files
    // (`melspectrogram.onnx`, `embedding_model.onnx`,
    // `hey_jarvis_v0.1.onnx`) ship pre-bundled in
    // `app/src/main/assets/` — Apache 2.0, no signup, no per-user
    // configuration needed.
    implementation(libs.rementia.openwakeword)

    // M5+ deps deferred until their milestones land:
    //   CameraX (take_photo), SQLCipher (Observe vault), Argon2 (Secret pw)
}
