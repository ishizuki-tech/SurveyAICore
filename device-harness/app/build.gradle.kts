plugins {
    id("com.android.application")
}

android {
    namespace = "com.negi.surveyaicore.c5harness"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.negi.surveyaicore.c5harness"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(files("libs/survey-ai-core-release.aar"))
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.16.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
