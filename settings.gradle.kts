pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "lerix_android"
// lerix-core: everything but push. lerix-messaging: push (Firebase). lerix: both,
// the all-in-one artifact 1.x apps already use.
include(":lerix-core")
project(":lerix-core").projectDir = file("core")
include(":lerix-messaging")
project(":lerix-messaging").projectDir = file("messaging")
include(":lerix")
include(":example")
