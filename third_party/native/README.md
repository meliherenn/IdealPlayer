# Native source preparation

Selected build: `3.7.2-idealplayer-remediation.8`, AAR SHA-256
`9028ee526d11d132b5e77e1d7abe3a696d04d3dd4eba16fb8965820a5b316d13`.
Fourteen FFmpeg fixes and Libgcrypt T8211 remain. GnuTLS 3.8.13/Nettle 3.10.2,
SFTP/SMB/NFS backports, libpng 1.6.59, libxml2 2.15.4 and the AOM allocation
backport preserve existing features. Java/API/configuration and all four ABIs are
verified in `evidence/remediation-8-build.json`. Targeted physical-TV evidence and
scope are documented in `../../docs/VLC_TLS_RELEASE_2026-10-28.md`.

## Prepare sources

From the IdealPlayer repository root, with Python 3.12+, curl and GNU patch:

```sh
python3 scripts/prepare_native_sources.py jellyfin \
  --out build/native-sources/jellyfin
python3 scripts/prepare_native_sources.py vlc_candidate \
  --out build/native-sources/vlc-candidate
```

Each output must be a new directory. Downloads are cached in `build/native-source-cache`;
`--cache` can select another directory. A corrupt cached archive fails verification instead of
being accepted or overwritten. Extraction rejects traversal, external links and populated
destinations. All upstream files, including notices and build scripts, are retained. The VLC
preparation also applies the pinned JNI archive's 20 Android patches and copies the FFmpeg
tarball into the contrib cache.

Each output retains its exact `sources.lock.json` snapshot. `prepared-sources.json` records the source/lock digests and filename-discovered notice files.
This notice list is an aid to review, not an inventory of every linked component's license.
The source archives and notices do not establish binary reproducibility or complete delivery.

## Restore build revision metadata

Source archives do not contain `.git`. Before invoking VLC build scripts, initialize each
source tree at its exact upstream revision so `git describe` cannot resolve IdealPlayer's
parent repository. Run this only in the newly prepared directories:

```sh
git -C build/native-sources/vlc-candidate/libvlcjni init
git -C build/native-sources/vlc-candidate/libvlcjni fetch --depth=1 \
  https://code.videolan.org/videolan/libvlcjni.git f6d8607210adba08f504e4b065f18620ba652de6
git -C build/native-sources/vlc-candidate/libvlcjni reset --mixed FETCH_HEAD
git -C build/native-sources/vlc-candidate/libvlcjni/vlc init
git -C build/native-sources/vlc-candidate/libvlcjni/vlc fetch --depth=1 \
  https://github.com/videolan/vlc.git 4c608da16c33560821be7e5c1ad4e34bf5a65398
git -C build/native-sources/vlc-candidate/libvlcjni/vlc reset --mixed FETCH_HEAD
```

The mixed resets initialize each index while preserving the prepared working-tree patches.
Do not use `--hard`, re-run `get-vlc.sh`, or reset the application's checkout.

## Candidate build

The pinned upstream script requires NDK 27/28 for 64-bit and NDK 21 for 32-bit. The current
64-bit candidates use NDK `28.2.13676358`; the 32-bit candidates use `21.4.7075529`.
The build retains the original full contrib license
selection (`g`), preserving enabled features. Do not switch to a reduced license selection
to avoid reviewing dependencies. From the application root:

```sh
ANDROID_SDK=/absolute/path/to/Android/Sdk \
ANDROID_NDK=/absolute/path/to/Android/Sdk/ndk/28.2.13676358 \
ac_cv_prog_cc_c23=no MAKEFLAGS=-j2 sh build/native-sources/vlc-candidate/libvlcjni/buildsystem/compile-libvlc.sh \
  -a arm64 --license g
```

The C23 probe override keeps older contrib C sources on the compiler's supported pre-C23
mode when the host has Autoconf 2.73. Without it, regenerated configure scripts select
`-std=gnu23`, which rejects legacy function declarations in SMB2, mpg123 and TwoLAME. This
changes the language selection, not decoder or protocol availability.

Upstream builds its host tools and downloads further contrib sources. This command is not
offline/reproducible merely because the three top-level archives are pinned. Preserve all
actual contrib tarballs, patches, build logs, configuration and tool identities for delivery.
Use independent prepared trees for concurrent ABI builds; do not race shared contrib outputs.
Release needs `arm64-v8a`, `armeabi-v7a` and `x86_64`; debug test coverage also uses `x86`.
The upstream architecture arguments are `arm64`, `arm`, `x86_64` and `x86`, respectively.
For each independent tree, use the same command with its matching architecture and NDK.
When aggregating outputs, copy each ABI directory from that tree's `libvlc/jni/libs` into the
packaging tree's matching directory. Record every source/destination hash before assembly;
never combine outputs from different source locks. Preserve the selected `libc++_shared.so`
from the matching NDK alongside `libvlc.so` and `libvlcjni.so`.
After native compilation, package with JDK 17 and the application's pinned Gradle wrapper:

```sh
JAVA_HOME=/absolute/path/to/jdk17 ANDROID_HOME=/absolute/path/to/Android/Sdk \
  ./gradlew -p build/native-sources/vlc-candidate/libvlcjni :libvlc:assembleRelease \
  --no-daemon --max-workers=1
```

This packages only the ABIs present in that tree's `libvlc/jni/libs`. Verify the AAR entries
and their hashes; an ARM64-only artifact is not an all-ABI replacement.

The Jellyfin tree includes the exact Media3 and FFmpeg submodule sources. Its `build.sh` and
`.github/workflows/extension-build.yaml` specify JDK 17, NDK `26.1.10909125` and CMake `3.31.1`.
Run its native script from the `jellyfin` directory with `ANDROID_HOME` set, then build the
`:media3-ffmpeg-decoder:assembleRelease` Gradle task. Preserve its original GPL notice and all
nested notices. No Jellyfin rebuild or equality with the published AAR is asserted yet.

## Distribution and evidence

The source distribution includes all pinned contrib archives and runtime source JARs.
Use the adjacent `native-inputs` cache instead of requiring fresh third-party downloads.
Build/install/relink instructions and verification boundaries are in
`../../docs/NATIVE_LICENSE_REVIEW_2026-10-28.md` and `../../docs/SOURCE_DISTRIBUTION.md`.
Source publication must precede binary distribution. Device evidence is scoped by
candidate; the October 28 targeted checks do not claim a repeated full codec/TV suite.

## October 12 HLS follow-up

The `.3` package adds unmodified upstream fixes for CVE-2026-107695 and
CVE-2026-107697 in the enabled HLS demuxer, plus CVE-2026-107660 in the disabled
mbedTLS backend. No codec, protocol or demuxer is disabled by this change. Four-ABI
build reports and exact AAR/API verification are retained in
`third_party/native/evidence/remediation-3-build.json`.

`scripts/native_hls_regression.c` tests the actual FFmpeg static archives with bounded,
in-memory owned playlists. The ARM64 phone run rejects a recursive master child after
one child open, and rejects prohibited child extensions/protocols before the I/O callback.
It performs no network or local-file reads. This checks FFmpeg directly; it is separate
from end-to-end Media3/LibVLC playback and is not a whole native security clearance.
