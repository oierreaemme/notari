pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "voice-note-markdown"

include(":app")

include(":core:common")
include(":core:design")
include(":core:database")
include(":core:datastore")
include(":core:inference")
include(":core:asr")

include(":feature:capture")
include(":feature:notes")
include(":feature:notedetail")
include(":feature:settings")
include(":feature:onboarding")
