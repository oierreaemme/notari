plugins {
    alias(libs.plugins.voicenotemd.android.library)
    alias(libs.plugins.voicenotemd.android.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.voicenotemd.core.inference"

    defaultConfig {
        // The Gemma model is INT4 quantized — recompressing inside the APK / AAB would
        // bloat install size for no benefit. Cover both the legacy MediaPipe `.task`
        // extension (kept for safety in case a future loader needs it) and the new
        // LiteRT-LM `.litertlm` format that we use today.
        androidResources {
            @Suppress("UnstableApiUsage")
            noCompress += listOf("task", "litertlm", "tflite", "bin")
        }
    }
}

dependencies {
    implementation(project(":core:common"))

    implementation(libs.litertlm)

    implementation(libs.moshi)
    implementation(libs.moshi.kotlin)
    ksp(libs.moshi.codegen)

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.truth)
    testImplementation(libs.mockk)
}
