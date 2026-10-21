import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Your own application id goes in local.properties (not committed), e.g.
//   tabletpanel.applicationId=com.yourname.tabletpanel
// Keep it the same once the app is installed: a different id installs as a separate app.
val localProperties = Properties().also { props ->
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { props.load(it) }
}

android {
    namespace = "com.example.tabletpanel"
    compileSdk = 37

    defaultConfig {
        applicationId = localProperties.getProperty("tabletpanel.applicationId") ?: "com.example.tabletpanel"
        minSdk = 26
        // Held at 36: targeting 37 opts into Android 17's local-network permission, which would
        // need a runtime prompt before the app could reach the LAN server or browse mDNS.
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}
