plugins {
    alias(libs.plugins.voicenotemd.android.feature)
}

android {
    namespace = "com.voicenotemd.feature.notes"
}

dependencies {
    implementation(project(":core:database"))
}
