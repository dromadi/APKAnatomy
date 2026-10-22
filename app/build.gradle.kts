plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "com.apkanatomy.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.apkanatomy.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 20
        versionName = "0.1.0-alpha.20"
    }
    buildFeatures { compose = true }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Screenshots are recorded (not compared) on every run; CI uploads them for review.
        unitTests.all { it.systemProperty("roborazzi.test.record", "true") }
    }
    // UI tests analyse the same real fixture APKs as the data module's tests.
    sourceSets.getByName("test").resources.directories.add("../data/src/test/resources")
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":domain"))
    implementation(project(":data"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.compose)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    debugImplementation(platform(libs.compose.bom))
    debugImplementation(libs.compose.ui.test.manifest)
}
