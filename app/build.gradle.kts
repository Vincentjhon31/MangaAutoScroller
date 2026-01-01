plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.zynt.mangaautoscroller"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zynt.mangaautoscroller"
        minSdk = 23
        targetSdk = 36
        versionCode = 100
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // BuildConfig fields for update checking
        buildConfigField("String", "GITHUB_OWNER", "\"Vincentjhon31\"")
        buildConfigField("String", "GITHUB_REPO", "\"MangaAutoScroller\"")
        buildConfigField("String", "RELEASE_URL", "\"https://github.com/Vincentjhon31/MangaAutoScroller/releases/latest\"")
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packagingOptions {
        jniLibs {
            useLegacyPackaging = false
            // Add explicit configuration for 16 KB page size alignment
            pickFirsts += listOf("**/*.so")
        }
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/ASL2.0"
            )
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

    // ML Kit for text recognition (for adaptive scrolling)
    implementation("com.google.mlkit:text-recognition:16.0.0-beta4")

    // ONNX Runtime for ML bubble detection (offline manga text detection)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.16.3")

    // Coil for image loading
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Material Icons Extended - needed for FolderOpen icon
    implementation("androidx.compose.material:material-icons-extended:1.6.7")
    implementation("androidx.compose.material:material-icons-core:1.6.7")

    // For screen capture
    implementation("androidx.activity:activity-ktx:1.8.2")

    // Add Gson for JSON serialization
    implementation("com.google.code.gson:gson:2.10.1")

    // Retrofit for GitHub API (update checker)
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Coroutines for async operations
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}