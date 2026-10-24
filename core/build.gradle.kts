plugins {
    id("com.android.library")
    kotlin("android")
    id("maven-publish")
}

android {
    namespace = "com.lerix.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        // R8 rules for apps that don't add the optional Play app-update library.
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.github.lerixDev.lerix_android"
                artifactId = "lerix-core"
                version = project.findProperty("VERSION_NAME") as? String ?: "1.5.0"
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Optional: Release Control's usePlayInAppUpdate. Apps add it themselves; the SDK checks for it at runtime.
    compileOnly("com.google.android.play:app-update:2.1.0")

    testImplementation("junit:junit:4.13.2")
    // Real org.json on the JVM test classpath (android.jar only has stubs).
    testImplementation("org.json:json:20240303")
}
