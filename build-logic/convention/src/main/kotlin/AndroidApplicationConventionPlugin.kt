import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("com.android.application")
                apply("org.jetbrains.kotlin.android")
            }

            extensions.configure<ApplicationExtension> {
                configureKotlinAndroid(this)
                defaultConfig.targetSdk =
                    libs.findVersion("targetSdk").get().requiredVersion.toInt()
            }

            // :app has test infrastructure (testImplementation deps) but no
            // unit-test classes today. Allow `./gradlew test` to complete on
            // modules without discovered tests. Same rationale as the library
            // convention plugin — see AndroidLibraryConventionPlugin for context.
            tasks.withType<Test>().configureEach {
                failOnNoDiscoveredTests.set(false)
            }
        }
    }
}
