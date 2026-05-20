plugins {
    alias(libs.plugins.voicenotemd.android.feature)
}

android {
    namespace = "com.voicenotemd.feature.settings"
}

dependencies {
    implementation(project(":core:datastore"))
    implementation(project(":core:database"))

    // BiometricManager — used to query device-side support so the Settings toggle can
    // disable itself when biometrics aren't enrolled. See ADR 0013.
    implementation(libs.androidx.biometric)
}
