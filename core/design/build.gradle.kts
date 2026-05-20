plugins {
    alias(libs.plugins.voicenotemd.android.library)
    alias(libs.plugins.voicenotemd.android.compose)
}

android {
    namespace = "com.voicenotemd.core.design"
}

dependencies {
    // MentionsSection consumes the DateMention domain model. Importing :core:common is
    // also the established way feature modules already get domain types — :core:design
    // staying isolated would force ugly DTOs at every callsite.
    implementation(project(":core:common"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.compose.material3.windowsize)
    implementation(libs.compose.material.icons.extended)

    // Markwon — Markdown rendering on top of an AppCompat TextView. Local to :core:design
    // so feature modules don't have to know about the renderer's transitive deps.
    implementation(libs.markwon.core)
    implementation(libs.markwon.ext.tables)
    implementation(libs.markwon.ext.tasklist)
    implementation(libs.androidx.appcompat)
}
