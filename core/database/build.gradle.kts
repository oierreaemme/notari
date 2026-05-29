plugins {
    alias(libs.plugins.voicenotemd.android.library)
    alias(libs.plugins.voicenotemd.android.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.voicenotemd.core.database"
}

// Tell Room to write schema JSON under :core:database/schemas/ so we get versioned
// migrations under source control. Required because the @Database has exportSchema = true.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    implementation(project(":core:common"))

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // SQLCipher — encrypted DB via Room SupportOpenHelperFactory (ADR 0019)
    implementation(libs.sqlcipher.android)

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.truth)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)

    // Instrumented tests: DatabasePassphraseProvider exercises the real Android Keystore,
    // which Robolectric does not shadow — see DatabasePassphraseProviderTest (ADR 0019).
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner) // provides AndroidJUnitRunner
    androidTestImplementation(libs.truth)
}
