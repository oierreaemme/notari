plugins {
    alias(libs.plugins.voicenotemd.android.library)
}

android {
    namespace = "com.voicenotemd.core.common"
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.truth)
}
