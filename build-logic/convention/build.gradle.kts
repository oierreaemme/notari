plugins {
    `kotlin-dsl`
}

group = "com.voicenotemd.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    implementation(libs.ksp.gradle.plugin)
    implementation(libs.hilt.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "voicenotemd.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "voicenotemd.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidFeature") {
            id = "voicenotemd.android.feature"
            implementationClass = "AndroidFeatureConventionPlugin"
        }
        register("androidCompose") {
            id = "voicenotemd.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("androidHilt") {
            id = "voicenotemd.android.hilt"
            implementationClass = "AndroidHiltConventionPlugin"
        }
        register("jvmLibrary") {
            id = "voicenotemd.jvm.library"
            implementationClass = "JvmLibraryConventionPlugin"
        }
    }
}
