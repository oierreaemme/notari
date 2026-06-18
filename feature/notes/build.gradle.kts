plugins {
    alias(libs.plugins.voicenotemd.android.feature)
}

android {
    namespace = "com.voicenotemd.feature.notes"
}

dependencies {
    implementation(project(":core:database"))
    // SAF tree handling for the folder (Obsidian vault) export. Pure local file I/O.
    implementation(libs.androidx.documentfile)
}
