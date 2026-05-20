import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.kotlin.dsl.dependencies

/**
 * Configure Jetpack Compose and the Material 3 baseline for an Android module.
 */
internal fun Project.configureAndroidCompose(
    commonExtension: CommonExtension<*, *, *, *, *, *>,
) {
    commonExtension.apply {
        buildFeatures {
            compose = true
        }
    }

    val bom = libs.findLibrary("compose-bom").get()
    dependencies {
        add("implementation", platform(bom))
        add("androidTestImplementation", platform(bom))

        add("implementation", libs.findLibrary("compose-ui").get())
        add("implementation", libs.findLibrary("compose-ui-graphics").get())
        add("implementation", libs.findLibrary("compose-ui-tooling-preview").get())
        add("implementation", libs.findLibrary("compose-material3").get())

        add("debugImplementation", libs.findLibrary("compose-ui-tooling").get())
        add("debugImplementation", libs.findLibrary("compose-ui-test-manifest").get())

        add("androidTestImplementation", libs.findLibrary("compose-ui-test-junit4").get())
    }
}

@Suppress("UNUSED_PARAMETER")
private fun Project.unused(libs: VersionCatalog) {
    // placeholder so Kotlin DSL keeps the import alive in some IDE scenarios.
}
