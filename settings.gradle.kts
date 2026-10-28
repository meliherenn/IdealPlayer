pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    name = "verifiedNativeArtifacts"
                    url = uri("third_party/maven")
                }
            }
            filter { includeModule("org.videolan.android", "libvlc-all") }
        }
        google()
        mavenCentral()
    }
}

// Keep local and CI builds on the native bytes exercised by the compatibility matrix.
// A missing or modified vendored artifact must not fall back to an upstream version.
val nativeArtifactPrefix = "third_party/maven/org/videolan/android/libvlc-all/" +
    "3.7.2-idealplayer-remediation.8/libvlc-all-3.7.2-idealplayer-remediation.8"
mapOf(
    "aar" to "9028ee526d11d132b5e77e1d7abe3a696d04d3dd4eba16fb8965820a5b316d13",
    "pom" to "908675d62c35ccdacef29e9a6532c581202af4aa90d87f2eeb66efc78629cd0f"
).forEach { (extension, expected) ->
    val bytes = providers.fileContents(layout.settingsDirectory.file("$nativeArtifactPrefix.$extension"))
        .asBytes.get()
    val actual = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    check(actual == expected) { "Native artifact checksum mismatch: $nativeArtifactPrefix.$extension" }
}

rootProject.name = "IdealPlayer"
include(":app")
include(":benchmark")
