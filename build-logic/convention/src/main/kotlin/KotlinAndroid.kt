import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * Configure base Kotlin / Android options shared by every Android module.
 */
internal fun Project.configureKotlinAndroid(
    commonExtension: CommonExtension<*, *, *, *, *, *>,
) {
    commonExtension.apply {
        compileSdk = libs.findVersion("compileSdk").get().requiredVersion.toInt()

        defaultConfig {
            minSdk = libs.findVersion("minSdk").get().requiredVersion.toInt()
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
            isCoreLibraryDesugaringEnabled = true
        }
    }

    dependencies {
        add("coreLibraryDesugaring", libs.findLibrary("android-desugar-jdk-libs").get())
    }

    extensions.configure<KotlinAndroidProjectExtension> {
        compilerOptions {
            // Treat warnings as warnings, not errors, until the project stabilizes.
            // After v1 we tighten this with a CI flag.
            freeCompilerArgs.addAll(
                "-Xjsr305=strict",
                "-opt-in=kotlin.RequiresOptIn",
                "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            )
        }
    }
}

/**
 * Configure base Kotlin options for pure JVM modules (no Android).
 */
internal fun Project.configureKotlinJvm() {
    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    extensions.configure<KotlinJvmProjectExtension> {
        compilerOptions {
            freeCompilerArgs.addAll(
                "-Xjsr305=strict",
                "-opt-in=kotlin.RequiresOptIn",
            )
        }
    }
}
