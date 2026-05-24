plugins {
    alias(libs.plugins.voicenotemd.android.application)
    alias(libs.plugins.voicenotemd.android.compose)
    alias(libs.plugins.voicenotemd.android.hilt)
}

android {
    namespace = "com.voicenotemd.app"

    defaultConfig {
        applicationId = "com.voicenotemd"
        versionCode = 2
        versionName = "1.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Until we wire up signing config from a separate keystore.properties, debug-sign
            // for local release builds. CI replaces this with the real signing config.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    packaging {
        resources {
            excludes +=
                listOf(
                    "/META-INF/{AL2.0,LGPL2.1}",
                    "/META-INF/DEPENDENCIES",
                    "/META-INF/LICENSE*",
                    "/META-INF/NOTICE*",
                )
        }
    }
}

dependencies {
    // Force a newer kotlinx-metadata-jvm to support Kotlin 2.3+ metadata in Hilt
    implementation("org.jetbrains.kotlinx:kotlinx-metadata-jvm:0.9.0")

    // Feature modules
    implementation(project(":feature:capture"))
    implementation(project(":feature:notes"))
    implementation(project(":feature:notedetail"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:onboarding"))

    // Core modules
    implementation(project(":core:common"))
    implementation(project(":core:design"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:inference"))
    implementation(project(":core:asr"))

    // AndroidX runtime
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.startup.runtime)
    implementation(libs.androidx.window)

    // Biometric (Class 2/3) for the optional app-launch lock — see ADR 0013. The
    // library does not require any new manifest permission on minSdk 28.
    implementation(libs.androidx.biometric)

    // Material XML themes (only for the splash/launcher theme)
    implementation(libs.material)

    // Tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.truth)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
