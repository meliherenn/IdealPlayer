# Version 43 / 1.2.9 source and build instructions

Updated October 28, 2026. Current native dependency is LibVLC remediation.8, with preserved
Jellyfin FFmpeg, Media3, full VLC features and original upstream notices. Earlier .4–.7 artifacts
are historical. The source manifest, not the date in this filename, identifies the snapshot.

## Layout

- `application/`: complete app/benchmark sources, Gradle wrapper/configuration/locks, Room
  schemas, synthetic tests, selected vendored native artifact, original licenses and native preparation scripts.
- `native-inputs/`: original source archives, 63 contrib archives, 160 Maven source JARs,
  component notices, patches, per-ABI configuration and build provenance.
- `manifest.json`: SHA-256 file inventory. No signing keys, account data or Git history.

Original licenses and source-delivery scope are in `docs/NATIVE_LICENSE_REVIEW_2026-10-28.md`.
The source archive is the corresponding source for the exact release manifest, not a claim
that every upstream published AAR was reproduced bit-for-bit locally.

## Build/install the application

Use JDK 17, SDK 36 and the pinned Gradle wrapper. From `application/`, optionally create
`local.properties` with your Android SDK path (or use `ANDROID_HOME`). Metadata lookup can
use your own `TMDB_API_KEY`; no private configuration is distributed.

```sh
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease :app:bundleRelease :app:cyclonedxDirectBom
```

For a signed release, supply `IDEALPLAYER_RELEASE_STORE_FILE`, `IDEALPLAYER_RELEASE_STORE_PASSWORD`,
`IDEALPLAYER_RELEASE_KEY_ALIAS` and `IDEALPLAYER_RELEASE_KEY_PASSWORD` through the supported
private Gradle/environment/local configuration. Otherwise the release is unsigned. Use your own key.
Install the debug build with `adb install -r` into its separate debug application identity, or sign
and install a release on a separate device/profile. Android will reject updating an installation
signed by a different key; do not delete a user's app/data to bypass that mismatch.

## Prepare and rebuild/relink native libraries

From `application/`, use the bundled source cache rather than relying on an unpinned download:

```sh
python3 scripts/prepare_native_sources.py vlc_candidate --out build/vlc-source --cache ../native-inputs/sources
python3 scripts/prepare_native_sources.py jellyfin --out build/jellyfin-source --cache ../native-inputs/sources
cp ../native-inputs/contrib/* build/vlc-source/libvlcjni/vlc/contrib/tarballs/
```

These commands assemble the exact sources and apply the local JNI/FFmpeg/Gcrypt/TLS/SSH/SMB/NFS/
PNG/XML/AOM patches. The scripts refuse corrupt archives and populated output directories.
`third_party/native/README.md` explains restoring upstream Git revision metadata in the newly
prepared trees, without resetting the application checkout or discarding patches.

Build four independent trees, using `compile-libvlc.sh -a arm64|arm|x86_64|x86 --license g`.
Use NDK 28.2.13676358 for the 64-bit ABIs and NDK 21.4.7075529 for the 32-bit ABIs. Set
`ANDROID_SDK`, `ANDROID_NDK`, `MAKEFLAGS=-j2` and `ac_cv_prog_cc_c23=no`. Match the retained
`native-inputs/build-config/<ABI>` files. Host tools can be fetched from their official sources
by the upstream scripts; this is not advertised as a hermetic/offline toolchain installation.

Collect the four `libvlc/jni/libs/<ABI>` directories into one packaging tree and run
`:libvlc:assembleRelease` using JDK 17 and the application Gradle wrapper. Alternatively, preserve
all Java/resources from the selected AAR and replace only the 12 matching `jni/<ABI>/*.so` entries
with your rebuilt outputs. Do not mix ABIs or keep an obsolete `libvlcjni.so` beside a new LibVLC.

Place the replacement AAR/POM in the local Maven location selected by `settings.gradle.kts`.
If changing its version, update the version catalog and app lock; update the AAR/POM SHA-256 pins
in `settings.gradle.kts` to your actual replacement files. These pins protect build input integrity;
they are editable source, not a runtime restriction on modified copies. Rebuild/sign the app.
The supplied Kotlin/Java application sources permit complete relinking, not just binary-only replacement.

Jellyfin's own build script and CI file specify NDK 26.1.10909125, CMake 3.31.1 and JDK 17.
Its complete Media3/FFmpeg submodules and build configuration are included. Build its native decoder
and `:media3-ffmpeg-decoder:assembleRelease`, substitute your rebuilt dependency and rebuild the app.
AndroidX source JARs/native module sources are also retained with their exact revision metadata.

## Evidence and publication

The final artifact manifest binds APK, AAB, SBOM, R8 mapping and native symbols. Symbols must
match native build IDs. Targeted October 28 device checks and preserved earlier tests are listed
in `docs/VLC_TLS_RELEASE_2026-10-28.md`; passed suites were not repeated. Current publication
status must come from the verified source URL and Play Console, not from this build guide.
