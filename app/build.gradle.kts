import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.hilt)
    alias(libs.plugins.kotlin.serialization)
    kotlin("kapt")
}

val apiBaseUrl = providers.gradleProperty("API_BASE_URL")
    .orElse("https://zplanner-api.lucbao045.workers.dev/")
    .get()
    .trim()
val apiUri = URI(apiBaseUrl)
require(
    apiUri.scheme in listOf("http", "https") && apiUri.host != null &&
        apiUri.userInfo == null && apiUri.query == null && apiUri.fragment == null
) {
    "API_BASE_URL must be an absolute HTTP(S) URL without credentials, a query, or a fragment."
}
val retrofitBaseUrl = apiUri.toASCIIString().removeSuffix("/") + "/"

android {
    namespace = "com.example.zplanner"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.zplanner"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "API_BASE_URL", "\"$retrofitBaseUrl\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"504994159658-kelr3bllvp67caivm5afqg0gam8vu5vl.apps.googleusercontent.com\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    
    // Navigation & DI & Room
    implementation(libs.androidx.navigation.compose)
    implementation(libs.google.hilt.android)
    kapt(libs.google.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)

    // Credential Manager / Google Sign-In
    implementation(libs.cmgr)
    implementation(libs.cmgr.play.services.auth)
    implementation(libs.googleid)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    // Tests
    testImplementation(libs.junit)
    testImplementation("org.mockito:mockito-core:5.15.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
