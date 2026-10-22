plugins { alias(libs.plugins.android.library) }
android {
    namespace = "com.apkanatomy.data"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies { implementation(project(":domain")); implementation(project(":core:axml")); implementation(libs.coroutines); implementation(libs.dexlib); testImplementation(libs.smali); testImplementation(libs.junit) }
