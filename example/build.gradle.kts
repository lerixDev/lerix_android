plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.lerix.example"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lerix.example"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        // Point the example at another backend: ./gradlew :example:installDebug -PlerixUrl=http://10.0.2.2:3010/v1 -PlerixProjectId=... -PlerixApiKey=...
        buildConfigField("String", "LERIX_URL", "\"${project.findProperty("lerixUrl") ?: "https://api.lerix.dev/v1"}\"")
        buildConfigField("String", "LERIX_PROJECT_ID", "\"${project.findProperty("lerixProjectId") ?: "u2ip-111-2esw"}\"")
        buildConfigField("String", "LERIX_API_KEY", "\"${project.findProperty("lerixApiKey") ?: "6141d7ec-6860-414e-bd5f-96a056698be5"}\"")
        // -PlerixEnvironment=dev reaches surveys targeted to that environment. Empty: the SDK default.
        buildConfigField("String", "LERIX_ENVIRONMENT", "\"${project.findProperty("lerixEnvironment") ?: ""}\"")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    implementation(project(":lerix"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
