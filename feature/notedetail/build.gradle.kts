plugins {
    alias(libs.plugins.voicenotemd.android.feature)
}

android {
    namespace = "com.voicenotemd.feature.notedetail"
}

dependencies {
    implementation(project(":core:database"))
}
