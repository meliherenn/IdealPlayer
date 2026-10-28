# Dependency licensing and source distribution

**October 28 current candidate:** LibVLC `3.7.2-idealplayer-remediation.8`; full-feature GPLv3 distribution with original MIT/upstream rights preserved. The current review and build evidence are [native release](VLC_TLS_RELEASE_2026-10-28.md) and [distribution review](NATIVE_LICENSE_REVIEW_2026-10-28.md). Source release location: https://github.com/meliherenn/IdealPlayer/releases. Publication must be verified before distributing version 43. The notes below are historical checkpoints; they do not override the newer review or claim source is already public.

**Current update — October 13, 2026:** the selected package is LibVLC
`3.7.2-idealplayer-remediation.4`, AAR SHA-256
`e94e2fc02d754012d3e3c3f0e5d7aec29a3ee39560baf23002763547d6355334`.
The updated 312-file staging manifest is
`build/phone-geometry-2026-10-12/final/source-delivery-staging/manifest.json`.
It retains fourteen pinned upstream FFmpeg patches, the Java live-track snapshot patch,
four-ABI build evidence and HLS regression source. All native bytes are identical to `.3`.
Immutable archives are hardlinked locally; mutable patches, configuration and evidence are copied.
The manifest identifies every file by hash. This remains `PREPARED_NOT_RELEASE_APPROVED`;
the unresolved review/delivery obligations below still apply.

**October 6 current artifact:** the working tree embeds the locally rebuilt
`org.videolan.android:libvlc-all:3.7.2-idealplayer-remediation.2` AAR (AAR SHA-256
`6940e7cdf0c53a7c5875266a7dd5888e0e3374b10cf8da7496fbfdc50b208fba`; POM SHA-256
`23b02c9ecebaaf651d07a6a156ae1ec57aa5b3ff2dd14686f0a396a534776648`). The exact AAR/POM are
vendored under `third_party/maven/` with configuration-time checksum verification. The local
source-delivery staging manifest is `build/native-remediation-2026-10-04/source-delivery-staging/manifest.json`.
It contains the pinned native archives, patches, contrib archives, notices, AndroidX source
archives, toolchain notices and per-ABI build inputs. It is a review package, not a publication,
written source offer or complete relinking/install distribution.

Current owner decision, September 30, 2026: **retain Jellyfin FFmpeg and preserve playback
functionality** after the removal audit showed regressions. The prior exclusion and mandatory
closed-source replacement plan are superseded. The owner previously allowed an open project
if needed to retain functionality; prepare distribution under the applicable dependency terms.
No source publication or repository visibility change is performed by this decision. The existing
MIT license on the project's own source is retained; it does not relabel bundled GPL/LGPL code.
No claim of closed-source distribution compatibility is made for the retained package.

## Current native package declarations

| Resolved package | Recorded declaration | Source identity / remaining work |
|---|---|---|
| `org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1` | GPL v3 in the resolved POM | FFmpeg `37c90f55d8b6c3948c79449b04755527b4ae1351`; AndroidX media `7cc1056f840ce226598d3b990d4a6f7cd17e2831`; exact source archives and nested Media3/FFmpeg trees prepared; build equality and distribution review remain open |
| `org.videolan.android:libvlc-all:3.7.2-idealplayer-remediation.4` | Local POM preserves upstream declaration; review required | Same native bytes as `.3`; FFmpeg 8.1.2 with fourteen pinned upstream fixes; Java track snapshot correction; four ABI build reports and source staging retained |
| `androidx.datastore:datastore-core-android:1.2.1` | See resolved SBOM/POM | Preserve the exact AndroidX release source/build information for its packaged native library |
| `androidx.graphics:graphics-path:1.0.1` | See resolved SBOM/POM | Preserve the exact AndroidX release and linked source/build information for its packaged native library |

The upstream `libvlc-all:3.7.2` entry in the native ledger is historical. The current
release resolves only the hash-pinned `.4` artifact above. Historical candidate test
results must not be transferred to it without checking the tested artifact identity.

The Jellyfin FFmpeg audio extension is restored in the dependency catalog, release lock and
custom Media3 audio renderer. The native ledger includes its exact AAR identity; it is no longer
excluded. Media3 again uses platform decoders plus the software audio renderer. Unsupported
primary-track detection and configured VLC fallback are retained for formats neither renderer
can decode. `FFMPEG_REMOVAL_REVIEW.md` preserves the removal experiment; its platform-only APK
must not be mistaken for the restored candidate.

The retained AAR hashes are in `scripts/native_review.json`. Package declarations are not a complete
license inventory of statically linked code or `libc++_shared.so`. The source repositories below
are provenance references, not substitutes for an assembled corresponding-source distribution.
Both Jellyfin and LibVLC still need the recorded exact-build licensing/security review.

## Distribution deliverable

For the final, verified AAB, prepare a reviewed dependency compliance package containing:

1. A reproducibility archive of the application's clean release commit, build scripts,
   lockfiles and tool versions, and the application/native corresponding sources required for
   the reviewed GPL/LGPL distribution. Keep existing notices. Exclude signing keys, local properties, provider inputs, personal test data and private
   logs from any publicly delivered materials.
2. Exact native component sources, nested dependencies, applied patches and build/configuration
   scripts for the AARs actually embedded in that AAB. Do not substitute the rejected LibVLC
   candidate or a moving upstream branch. Repository links and Maven POMs alone are insufficient.
3. Required copyright/license notices, including the individual linked components. Include the
   applicable relinking/install/build materials determined by review; do not claim that publishing
   only the app's Kotlin source resolves native distribution obligations.
4. A manifest tying the application commit, AAB, SBOM, native inventory, AAR hashes and every source
   archive/patch to SHA-256 digests. Include the tested JDK/SDK/NDK and reproducible build commands.
5. The reviewed distribution mechanism for required component sources and relinking/install
   materials. Use immutable versions/hashes; add only real, available links to release notices.
   Do not equate a component-source obligation with an owner decision to publish the whole app.

No written source offer, publication or complete license clearance is asserted here. Complete
the applicable source, notices and delivery obligations before release; retaining an open project
alone does not complete them. Do not waive findings or silently change licensing to pass a gate.

## Implemented preparation and open gate

- Gradle merges common Apache/LGPL metadata entries instead of discarding them.
- A dedicated asset-copy task retains `licenses/` and excludes native build sources/patches from
  APK assets. Debug and release asset merges preserve the original license bytes and paths.
- The release SBOM is configured to include available license texts.
- GPL-3.0, LGPL-2.1 and Apache-2.0 license texts are retained with publisher provenance in
  `third_party/licenses/`; these common texts do not replace component-specific copyright notices.
- Hash-pinned Jellyfin root, Media3 and FFmpeg sources are assembled with upstream build scripts
  and notices. `third_party/native/sources.lock.json` records their SHA-256 digests.
  `scripts/prepare_native_sources.py` regenerates this tree and records notice-file hashes.
- The current `.4` LibVLC source staging retains all four ABI build configurations and outputs,
  the fourteen applied FFmpeg patches and Java track snapshot patch, the 63-contrib archive inventory, AndroidX native source
  archives and NDK notices. The manifest is intentionally marked `PREPARED_NOT_RELEASE_APPROVED`;
  linked-object inventory, legal review and delivery mechanism review are still required.
- The earlier nine- and eleven-patch candidates are historical. The current recipe uses fourteen
  upstream patches; its locked inputs and four-ABI build evidence identify the current AAR.
- The candidate contrib inventory retains 63 downloaded source archives, three Git-revision
  sidecars and 203 notice files (including the JPEG README terms). All are hash-bound in
  `contrib-source-manifest.json`; this source inventory does not yet identify the final linked
  object set or certify distribution compliance. Of those archives, 60 match SHA-512 checksums
  in the pinned/patched contrib recipes. The remaining three Git archives have matching pinned
  recipe revisions, archive commit headers and revision sidecars. This is recorded in
  `contrib-checksum-evidence.json`; Git headers/sidecars are provenance, not signed authentication.
- Source preparation instructions are in `third_party/native/README.md`; local evidence is under
  `build/native-remediation-2026-10-03/`. Candidate compilation and combined AAR assembly now
  succeed for all four ABIs; the scoped x86_64 phone and x86 TV native journeys pass. Full
  linked-source/license inventory, physical/release runtime acceptance, binary/source
  correspondence and delivery review remain open.
- Native advisory review is independent. The existing security findings and runtime failures
  cannot be cleared by publishing source or including license texts.
- Debug/test and local release APK/AAB/SBOM/native inventory were rebuilt and verified by October 3.
  Their hashes and retained bytes are in `build/phase-g-continuation-2026-10-03/artifact-manifest.json`.
  Reviewed corresponding-source delivery, the frozen upload candidate and full acceptance remain pending.

## Primary references

- [GNU GPL v3 text](https://www.gnu.org/licenses/gpl-3.0.html), especially sections 4–6.
- [GNU LGPL v2.1 text](https://www.gnu.org/licenses/lgpl-2.1.html), especially sections 4–6.
- [Jellyfin exact release](https://github.com/jellyfin/jellyfin-androidx-media/tree/v1.9.0%2B1).
- [VideoLAN source revision](https://code.videolan.org/videolan/libvlcjni/-/tree/f6d8607210adba08f504e4b065f18620ba652de6).
- `NATIVE_DEPENDENCY_REVIEW.md` records the exact package/POM hashes and bounded review findings.
