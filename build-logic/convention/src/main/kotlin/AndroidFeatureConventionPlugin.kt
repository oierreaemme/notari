import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Apply this plugin to a `:feature:*` module. It composes the library + compose + hilt
 * conventions and adds dependencies that every feature is expected to need.
 */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("voicenotemd.android.library")
                apply("voicenotemd.android.compose")
                apply("voicenotemd.android.hilt")
            }

            dependencies {
                add("implementation", project(":core:common"))
                add("implementation", project(":core:design"))

                add("implementation", libs.findLibrary("androidx-core-ktx").get())
                add("implementation", libs.findLibrary("androidx-lifecycle-runtime-ktx").get())
                add("implementation", libs.findLibrary("androidx-lifecycle-viewmodel-compose").get())
                add("implementation", libs.findLibrary("androidx-lifecycle-runtime-compose").get())
                add("implementation", libs.findLibrary("androidx-navigation-compose").get())
                add("implementation", libs.findLibrary("hilt-navigation-compose").get())
                add("implementation", libs.findLibrary("compose-material-icons-extended").get())
                add("implementation", libs.findLibrary("kotlinx-coroutines-android").get())

                add("testImplementation", libs.findLibrary("junit").get())
                add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
                add("testImplementation", libs.findLibrary("turbine").get())
                add("testImplementation", libs.findLibrary("mockk").get())
                add("testImplementation", libs.findLibrary("truth").get())
            }
        }
    }
}
