import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.androidx.baselineprofile)
    id("org.cyclonedx.bom") version "3.0.1"
}

val coverageEnabled = providers.gradleProperty("coverage").map(String::toBoolean).getOrElse(false)

val licenseAssetsDirectory = layout.buildDirectory.dir("generated/licenseAssets")
val prepareLicenseAssets by tasks.registering(Sync::class) {
    from(rootProject.file("third_party/licenses")) { into("licenses") }
    into(licenseAssetsDirectory)
}

dependencyLocking {
    lockMode = org.gradle.api.artifacts.dsl.LockMode.STRICT
}
configurations.configureEach {
    if (name == "releaseRuntimeClasspath") resolutionStrategy.activateDependencyLocking()
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) load(file.inputStream())
}

val versionProperties = Properties().apply {
    val file = rootProject.file("version.properties")
    if (file.exists()) load(file.inputStream())
}

fun buildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val appVersionCode = providers.gradleProperty("VERSION_CODE").orNull
    ?: System.getenv("VERSION_CODE")
    ?: versionProperties.getProperty("VERSION_CODE", "1")

val appVersionName = providers.gradleProperty("VERSION_NAME").orNull
    ?: System.getenv("VERSION_NAME")
    ?: versionProperties.getProperty("VERSION_NAME", "1.0.0")

val parsedAppVersionCode = appVersionCode.toIntOrNull()
    ?.takeIf { it > 0 }
    ?: error("VERSION_CODE must be a positive integer, but was '$appVersionCode'")

val parsedAppVersionName = appVersionName.trim()
    .takeIf(String::isNotEmpty)
    ?: error("VERSION_NAME must not be blank")

val remoteSetupApiBaseUrl = providers.gradleProperty("REMOTE_SETUP_API_BASE_URL").orNull
    ?: System.getenv("REMOTE_SETUP_API_BASE_URL")
    ?: localProperties.getProperty("REMOTE_SETUP_API_BASE_URL", "")

val remoteSetupWebBaseUrl = providers.gradleProperty("REMOTE_SETUP_WEB_BASE_URL").orNull
    ?: System.getenv("REMOTE_SETUP_WEB_BASE_URL")
    ?: localProperties.getProperty("REMOTE_SETUP_WEB_BASE_URL", "")

// Protocol v1 exposes/consumes the payload through a public pairing code. Keep every app
// variant offline until owner-authenticated claim/ACK and scheduled expiry are validated.
val remoteSetupEnabled = false

fun releaseSigningProperty(name: String): String? =
    providers.gradleProperty(name).orNull
        ?: System.getenv(name)
        ?: localProperties.getProperty(name)

val releaseStoreFile = releaseSigningProperty("IDEALPLAYER_RELEASE_STORE_FILE")
val releaseStorePassword = releaseSigningProperty("IDEALPLAYER_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseSigningProperty("IDEALPLAYER_RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseSigningProperty("IDEALPLAYER_RELEASE_KEY_PASSWORD")

val releaseSigningProperties = listOf(
    "IDEALPLAYER_RELEASE_STORE_FILE" to releaseStoreFile,
    "IDEALPLAYER_RELEASE_STORE_PASSWORD" to releaseStorePassword,
    "IDEALPLAYER_RELEASE_KEY_ALIAS" to releaseKeyAlias,
    "IDEALPLAYER_RELEASE_KEY_PASSWORD" to releaseKeyPassword
)
val configuredReleaseSigningProperties = releaseSigningProperties.filter { (_, value) ->
    !value.isNullOrBlank()
}
if (
    configuredReleaseSigningProperties.isNotEmpty() &&
    configuredReleaseSigningProperties.size != releaseSigningProperties.size
) {
    error(
        "Release signing is partially configured. Set all of " +
            releaseSigningProperties.joinToString { (name) -> name } +
            ", or remove all of them."
    )
}
val hasReleaseSigning = configuredReleaseSigningProperties.size == releaseSigningProperties.size

android {
    namespace = "com.idealplayer.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.idealplayer.app"
        minSdk = 26
        targetSdk = 36
        versionCode = parsedAppVersionCode
        versionName = parsedAppVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "TMDB_API_KEY", buildConfigString(localProperties.getProperty("TMDB_API_KEY", "")))
        buildConfigField("boolean", "REMOTE_SETUP_ENABLED", remoteSetupEnabled.toString())
        buildConfigField("String", "REMOTE_SETUP_API_BASE_URL", buildConfigString(remoteSetupApiBaseUrl.trimEnd('/')))
        buildConfigField("String", "REMOTE_SETUP_WEB_BASE_URL", buildConfigString(remoteSetupWebBaseUrl.trimEnd('/')))

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
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

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all { test ->
                // Force an invariant locale for the test JVM. On a Turkish-locale machine,
                // conscrypt (pulled in by Robolectric) lowercases "LINUX" to "lınux" (dotless ı)
                // when resolving its bundled native lib, which then fails to load.
                test.systemProperty("user.language", "en")
                test.systemProperty("user.country", "US")
                if (coverageEnabled) {
                    test.extensions.configure<org.gradle.testing.jacoco.plugins.JacocoTaskExtension> {
                        // Robolectric's sandbox classes have no source location. Without this,
                        // successful repository/player tests misleadingly report zero coverage.
                        isIncludeNoLocationClasses = true
                        includes = listOf("com.idealplayer.app.*")
                    }
                    // Agent instrumentation changes wall-clock timings. The ordinary unit-test
                    // gate still runs this class; exclude it only from the coverage measurement.
                    test.filter.excludeTestsMatching("*.CatalogStressBenchmarkTest")
                }
            }
        }
    }

    // MigrationTestHelper reads the exported Room schemas from the test APK's assets.
    sourceSets {
        getByName("main").assets.srcDir(licenseAssetsDirectory)
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    packaging {
        resources {
            // Preserve upstream license texts when multiple runtime artifacts ship the
            // same entry. This does not replace the linked-native/source-offer review.
            merges += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += setOf(
                "lib/*/libc++_shared.so"
            )
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = coverageEnabled
            isDebuggable = true
            applicationIdSuffix = ".debug"
            // API 26/31 Android TV system images are available locally as x86-only images.
            // Keep x86 in the debug APK for the connected emulator matrix without expanding
            // the Play release AAB's production ABI set.
            ndk {
                abiFilters += setOf("x86", "x86_64")
            }
        }
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

}

androidComponents {
    finalizeDsl { extension ->
        // Opt-in isolated identity for physical-device measurements with user accounts installed.
        providers.gradleProperty("idealplayerBenchmarkApplicationIdSuffix").orNull?.let { suffix ->
            require(suffix.matches(Regex("\\.[a-z][a-z0-9]*")))
            extension.buildTypes.getByName("benchmarkRelease").applicationIdSuffix = suffix
        }
        // The generated benchmark target must also install on the x86-only API 26/31
        // emulator images used by CI. The Play release ABI set remains in defaultConfig.
        extension.buildTypes.getByName("benchmarkRelease").ndk.abiFilters.addAll(
            listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        )
    }
    onVariants { variant ->
        if (variant.name == "benchmarkRelease") {
            // The Baseline Profile plugin derives this variant from release. Add a second,
            // benchmark-only manifest layer so the fixture receiver never enters Play builds.
            variant.sources.manifests.addStaticManifestFile(
                "src/benchmarkRelease/AndroidManifest.xml"
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

baselineProfile {
    // Release builds must stay reproducible on ordinary CI runners. Profile generation is an
    // explicit device-lab task; generated rules are then stored in source and packaged here.
    automaticGenerationDuringBuild = false
    saveInSrc = true
}

tasks.named("preBuild") {
    dependsOn(prepareLicenseAssets)
}

tasks.cyclonedxDirectBom {
    // The plugin can reuse a stale inventory after a runtime dependency/lock update.
    // An explicitly requested release inventory must resolve the current graph every time.
    outputs.upToDateWhen { false }
    includeConfigs = listOf("releaseRuntimeClasspath")
    projectType = org.cyclonedx.model.Component.Type.APPLICATION
    componentName = "IdealPlayer"
    componentGroup = "com.idealplayer.app"
    componentVersion = parsedAppVersionName
    includeLicenseText = true
    jsonOutput = layout.buildDirectory.file("reports/sbom/release-runtime.cdx.json")
    xmlOutput.unsetConvention()
}

dependencies {
    // Compose BOM
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.animation)
    implementation(libs.compose.foundation)
    implementation(libs.compose.runtime)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.profileinstaller)
    baselineProfile(project(":benchmark"))

    // AndroidX
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.splashscreen)
    implementation(libs.appcompat)

    // Lifecycle
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel)

    // WorkManager + Hilt integration
    implementation(libs.work.runtime)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Security Crypto (EncryptedSharedPreferences for PIN storage)
    implementation(libs.security.crypto)
    implementation(libs.zxing.core)

    // Navigation
    implementation(libs.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // DataStore
    implementation(libs.datastore.preferences)

    // Network
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // Playback
    implementation(libs.libvlc)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.ui)
    implementation(libs.media3.common)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.ffmpeg.decoder)

    // Image Loading
    implementation(libs.coil)

    // Logging
    implementation(libs.timber)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.room.testing)
    testImplementation(libs.test.core.ktx)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.ext)
    androidTestImplementation(libs.test.core.ktx)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(composeBom)
    androidTestImplementation(libs.compose.ui.test)
    debugImplementation(libs.compose.ui.test.manifest)
}
