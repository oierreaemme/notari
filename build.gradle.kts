// Top-level build file. Per-module configuration is in module build.gradle.kts files
// or in the convention plugins under :build-logic.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.roborazzi) apply false
}

// Apply detekt and ktlint to every subproject so the root-level invocations
// `./gradlew detekt` and `./gradlew ktlintCheck` — which is what `.github/workflows
// /ci.yml` runs in the static-analysis job — actually have tasks to execute. Without
// this block the plugins are declared in the buildscript classpath (above) but never
// applied to any module, and the CI commands fail with "Task 'detekt' not found".
//
// We hook on the Kotlin plugin id rather than applying unconditionally so that the
// :build-logic composite build (which is not a subproject of this root) is not
// affected, and so we never try to lint a module that has no Kotlin source. This is
// idempotent — applying the plugin twice is a no-op.
val detektPluginId = libs.plugins.detekt.get().pluginId
val ktlintPluginId = libs.plugins.ktlint.get().pluginId

subprojects {
    plugins.withId("org.jetbrains.kotlin.android") {
        pluginManager.apply(detektPluginId)
        pluginManager.apply(ktlintPluginId)
    }
    plugins.withId("org.jetbrains.kotlin.jvm") {
        pluginManager.apply(detektPluginId)
        pluginManager.apply(ktlintPluginId)
    }
}

// Helper task to print the resolved Gradle/Kotlin versions — useful for CI debugging.
tasks.register("toolchainInfo") {
    group = "help"
    description = "Print the toolchain versions used by this build."
    doLast {
        println("Gradle:  ${gradle.gradleVersion}")
        println("Kotlin:  ${libs.versions.kotlin.get()}")
        println("AGP:     ${libs.versions.agp.get()}")
        println("Java:    ${libs.versions.java.get()}")
    }
}
