import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("com.android.library")
                apply("org.jetbrains.kotlin.android")
            }

            extensions.configure<LibraryExtension> {
                configureKotlinAndroid(this)
                defaultConfig.targetSdk =
                    libs.findVersion("targetSdk").get().requiredVersion.toInt()
                defaultConfig.testInstrumentationRunner =
                    "androidx.test.runner.AndroidJUnitRunner"
                testOptions.unitTests.isIncludeAndroidResources = true
            }

            // Library modules legitimately have no unit tests sometimes (e.g.
            // :core:design is pure Compose UI covered by screenshot tests via
            // Roborazzi, not JUnit). Gradle 9+ fails the test task by default when
            // no tests are discovered — we relax that for library modules so
            // a module with zero @Test methods doesn't break `./gradlew test`.
            tasks.withType<Test>().configureEach {
                failOnNoDiscoveredTests.set(false)
            }
        }
    }
}
