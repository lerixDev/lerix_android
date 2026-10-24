plugins {
    id("com.android.library")
    kotlin("android")
    id("maven-publish")
}

// The all-in-one artifact: lerix-core + lerix-messaging, so apps on
// `com.github.lerixDev.lerix_android:lerix:1.x` keep push without changes.
android {
    namespace = "com.lerix.sdk.all"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    publishing {
        singleVariant("release")
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.github.lerixDev.lerix_android"
                artifactId = "lerix"
                version = project.findProperty("VERSION_NAME") as? String ?: "1.5.0"
            }
        }
    }
}

dependencies {
    api(project(":lerix-core"))
    api(project(":lerix-messaging"))
}
