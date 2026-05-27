plugins {
    alias(libs.plugins.voicenotemd.android.library)
    alias(libs.plugins.voicenotemd.android.hilt)
}

android {
    namespace = "com.voicenotemd.core.asr"

    defaultConfig {
        ndk {
            // Spike: build only the Pixel 6a's ABI to keep native build time + APK size down.
            // Add armeabi-v7a / x86_64 before any release. (ADR 0018 phase 2.)
            abiFilters += "arm64-v8a"
        }
    }

    // Builds whisper.cpp (vendored submodule) + the JNI bridge via CMake.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

dependencies {
    implementation(project(":core:common"))

    implementation(libs.kotlinx.coroutines.android)

    // On-device continuous-streaming ASR (ADR 0018).
    implementation(libs.vosk.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.truth)
    testImplementation(libs.mockk)
}
