import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.hilt)
}

// Capability Expansion v3 — Meta DAT SDK auth values. The SDK's config
// reader literally rejects the string "0" as a placeholder (verified
// by disassembling DatConfiguration$Companion) so the get-started doc's
// "use 0 for APPLICATION_ID in Developer Mode" is a lie — Developer
// Mode still needs a real ID + token from
//   https://wearables.developer.meta.com/
// We read both from `local.properties` (gitignored) so they never end
// up in source control. Empty values are tolerated — the SDK will just
// keep registrationState at UNAVAILABLE until they're filled in.
val mwdatLocalProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val mwdatApplicationId = mwdatLocalProps.getProperty("mwdat_application_id", "")
val mwdatClientToken = mwdatLocalProps.getProperty("mwdat_client_token", "")

android {
    namespace = "com.mythara"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mythara"
        // minSdk bumped 26 → 29 for Meta DAT Camera (mwdat-camera:0.7.0
        // declares minSdk=29). The target cluster (Pixel 10 Pro, Pixel
        // 9 Pro Fold, Pixel Tablet) is all far above this so no devices
        // drop off. The original 26 floor was chosen for legacy phones
        // we don't actually run on.
        minSdk = 29
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

        // Meta DAT SDK metadata placeholders — substituted into the
        // <meta-data> tags in AndroidManifest.xml. Values come from
        // local.properties (see top of file).
        manifestPlaceholders["mwdat_application_id"] = mwdatApplicationId
        manifestPlaceholders["mwdat_client_token"] = mwdatClientToken
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
            "-opt-in=androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi",
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
            // JSch + jspecify both ship a META-INF OSGI manifest at
            // identical paths; the merger crashes on the collision.
            // Picking the first one is fine — we don't consume OSGI
            // metadata at runtime.
            pickFirsts += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
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
    implementation(libs.androidx.material3.window.sizeclass)
    // androidx.window — exposes FoldingFeature (FLAT vs HALF_OPENED)
    // for the rose-bloom fold-open animation. WindowSizeClass alone
    // gives us width buckets but not posture transitions.
    implementation(libs.androidx.window)
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

    // Health Connect — Android's modern unified health data API.
    // Replaces the deprecated Google Fit. Read-only: steps, heart rate,
    // sleep, distance, weight, etc. Feeds the periodic
    // HealthLearningWorker so the assistant grows a long-term view of
    // the user's activity / sleep / heart patterns over time.
    implementation(libs.androidx.health.connect.client)

    // Wearable Data Layer — phone-side receiver for the Wear OS
    // companion app's push-to-talk transcripts. The Wear module
    // (:wear) speaks the same client API to send messages back.
    implementation(libs.play.services.wearable)

    // JSch (Maxim Wiede fork) — pure-Java SSH client used by the
    // `linux_vm` agent tool to reach the Android 15 Linux Terminal
    // Debian VM. The Android system image does not bundle an `ssh`
    // binary on PATH, so a JVM SSH client is the only path that
    // works without external setup beyond the Linux Terminal's own
    // openssh-server install. Modernised fork (over the original
    // jcraft jsch 0.1.55) so it supports current key formats
    // (ed25519, ecdsa) and recent algorithm negotiation.
    implementation("com.github.mwiede:jsch:0.2.20")

    // Shizuku — privileged-shell shim. The user installs the Shizuku
    // app + bootstraps it once via adb (or wireless debugging), which
    // spawns a host process running with shell UID. Mythara can then
    // ping that process to execute `settings put …`, `pm …`, etc. as
    // if it had WRITE_SECURE_SETTINGS — without modifying /system,
    // without persistent root. Used by the cosmetic tool (Phase 8)
    // to apply non-invasive Android system tweaks like font scale,
    // dark mode, accent color, gesture-nav mode.
    // Degrades gracefully: when Shizuku is not installed or not
    // running, the cosmetic tool returns a setup-card and the rest
    // of Mythara is unaffected.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

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

    // ML Kit Face Detection — on-device, bundled model. Powers the
    // front-camera face tracking on the Face avatar screen: head euler
    // angles drive the point-cloud head's pose, eye-open probabilities
    // drive its blink. Capability Expansion v3 reuses the same detector
    // (with PERFORMANCE_MODE_ACCURATE) for glasses-photo face boxes
    // before MobileFaceNet runs identity matching.
    implementation(libs.google.mlkit.face.detection)


    // CameraX — headless one-shot ImageCapture for the M5 `take_photo`
    // tool. We pull core + camera2 + lifecycle (no view/preview); the
    // assistant captures silently without rendering a viewfinder.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)

    // Capability Expansion v3 — Meta DAT SDK for phone↔Display Glasses.
    // core: registration / sessions / device discovery.
    // camera: video stream + capturePhoto() from the glasses POV.
    // display: render UI on the glasses (text / icons / buttons /
    //          images / video) with button-click callbacks routed back.
    //
    // Pulled from Meta's GitHub Packages registry — needs a read:packages
    // PAT in `github_token=...` (local.properties, gitignored) or the
    // GITHUB_TOKEN env var. See settings.gradle.kts maven block.
    implementation(libs.mwdat.core)
    implementation(libs.mwdat.camera)
    implementation(libs.mwdat.display)

    // Capability Expansion v3 — TensorFlow Lite for MobileFaceNet
    // (128-D face embeddings). Base runtime gives us Interpreter for
    // the raw .tflite file; task-vision is reserved for future image-
    // helper utilities. Model weights are lazy-downloaded into
    // filesDir/face/mobilefacenet.tflite on first use, so the deps
    // themselves only add ~3 MB to the APK.
    implementation(libs.tflite.runtime)
    implementation(libs.tflite.task.vision)

    // M5+ deps deferred until their milestones land:
    //   SQLCipher (Observe vault), Argon2 (Secret pw)
}
