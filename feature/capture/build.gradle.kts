plugins {
    alias(libs.plugins.voicenotemd.android.feature)
}

android {
    namespace = "com.voicenotemd.feature.capture"
}

dependencies {
    implementation(libs.compose.material.icons.extended)
    implementation(project(":core:asr"))
    implementation(project(":core:inference"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
}
