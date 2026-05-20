import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Apply this plugin to any Android module (application or library) that uses Compose.
 *
 * It pulls in the Kotlin Compose plugin (Kotlin 2.0+ Compose Compiler) and the
 * Compose BOM-managed dependencies via [configureAndroidCompose].
 */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

            extensions.findByType(ApplicationExtension::class.java)?.let {
                configureAndroidCompose(it)
                return
            }
            extensions.findByType(LibraryExtension::class.java)?.let {
                configureAndroidCompose(it)
            }
        }
    }
}
