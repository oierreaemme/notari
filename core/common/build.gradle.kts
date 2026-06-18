plugins {
    alias(libs.plugins.voicenotemd.android.library)
}

android {
    namespace = "com.voicenotemd.core.common"
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // JSR-330 @Qualifier for the Gemma-vs-whisper model repository bindings (see
    // ModelRepositoryQualifiers). Exposed via `api` so :app and :feature:settings can
    // reference the same annotations when wiring/injecting. No Hilt in :core:common.
    api(libs.javax.inject)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.truth)
}
