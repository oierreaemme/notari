plugins {
    alias(libs.plugins.voicenotemd.android.feature)
}

android {
    namespace = "com.voicenotemd.feature.onboarding"
}

dependencies {
    implementation(libs.compose.material.icons.extended)
}
