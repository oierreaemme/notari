plugins {
    alias(libs.plugins.voicenotemd.android.library)
    alias(libs.plugins.voicenotemd.android.hilt)
}

android {
    namespace = "com.voicenotemd.core.asr"

    defaultConfig {
        ndk {
            // Native ABIs for the bundled whisper.cpp library.
            //
            // - arm64-v8a:    primary target — all 64-bit Android phones since 2018, including
            //                 every device we ship for in practice (Pixel 6a is our reference).
            // - armeabi-v7a:  32-bit ARM legacy. Whisper still works here on the quantized
            //                 (`q5_1`) models we ship by default; f16 base/small are noticeably
            //                 slower because NEON-FP16 acceleration is 64-bit-only.
            // - x86_64:       so the app installs on the Play Console / reviewer / emulator
            //                 environments that run x86_64 AOSP images.
            //
            // Build time and APK size grow proportionally to the number of ABIs (each ABI gets
            // its own ~7 MB `libwhisper.so` + JNI bridge). A future improvement is APK splits
            // by ABI so each device downloads only its own copy — tracked separately.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        // 16 KB page-size support (Android 15+ / Play requirement). Our native .so files
        // (libwhisper, libggml-base, libwhisper_jni) must have their ELF LOAD segments
        // aligned to 16 KB or they fail the alignment check on 16 KB-page devices. NDK r27
        // gates this behind a flag (r28 makes it the default); ANDROID_SUPPORT_FLEXIBLE_
        // PAGE_SIZES=ON applies it to every CMake target — including the vendored whisper.cpp
        // / ggml submodule targets we don't edit directly. See https://developer.android.com/
        // guide/practices/page-sizes. (Third-party .so from LiteRT-LM / SQLCipher / DataStore
        // need their own 16 KB-aligned releases — tracked separately.)
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
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
