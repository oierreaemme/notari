plugins {
    alias(libs.plugins.voicenotemd.android.library)
    alias(libs.plugins.voicenotemd.android.hilt)
}

android {
    namespace = "com.voicenotemd.core.datastore"
}

dependencies {
    implementation(project(":core:common"))

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.truth)
}
