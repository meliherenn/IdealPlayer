import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // Versions are already placed on the shared build classpath by :app.
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
    id("androidx.baselineprofile")
}

android {
    namespace = "com.idealplayer.app.benchmark"
    compileSdk = 36
    targetProjectPath = ":app"

    defaultConfig {
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

baselineProfile {
    // A connected API 33+ TV emulator or physical TV can generate profiles without root.
    // CI compiles this module; generation is a release-candidate device-lab gate.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.test.runner)
    implementation(libs.test.ext)
    implementation(libs.uiautomator)
    implementation(libs.benchmark.macro.junit4)
}
