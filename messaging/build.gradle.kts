plugins {
    id("com.android.library")
    kotlin("android")
    id("maven-publish")
}

android {
    namespace = "com.lerix.sdk.messaging"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        // Keeps LerixNotifications, which lerix-core finds by class lookup.
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
                artifactId = "lerix-messaging"
                version = project.findProperty("VERSION_NAME") as? String ?: "1.5.0"
            }
        }
    }
}

dependencies {
    api(project(":lerix-core"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Pinned to match the Flutter plugin's own working Firebase BOM version.
    implementation(platform("com.google.firebase:firebase-bom:32.7.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")

    testImplementation("junit:junit:4.13.2")
    // Real org.json on the JVM test classpath (android.jar only has stubs).
    testImplementation("org.json:json:20240303")
}
