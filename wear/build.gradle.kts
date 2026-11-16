plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
apply(from = rootProject.file("app-common.gradle.kts"))

android {
    namespace = "app.depot.store"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.depot.store"
        minSdk = 30
        targetSdk = 34
        versionCode = rootProject.extra["depotVersionCode"] as Int
        versionName = rootProject.extra["depotVersionName"] as String
    }

    val storePath = project.extra["signingStoreFile"] as String?
    signingConfigs {
        if (storePath != null) {
            create("release") {
                storeFile = rootProject.file(storePath)
                storePassword = project.extra["signingStorePassword"] as String?
                keyAlias = project.extra["signingKeyAlias"] as String?
                keyPassword = project.extra["signingKeyPassword"] as String?
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (storePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    // play-services pulls in an old Fragment that breaks permission requests; pin a fixed one.
    implementation("androidx.fragment:fragment:1.8.6")
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.wear.compose:compose-material:1.4.1")
    implementation("androidx.wear.compose:compose-foundation:1.4.1")
}
