plugins {
    alias(libs.plugins.voicenotemd.android.library)
    alias(libs.plugins.voicenotemd.android.hilt)
}

android {
    namespace = "com.voicenotemd.core.asr"
}

dependencies {
    implementation(project(":core:common"))

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.truth)
    testImplementation(libs.mockk)
}
