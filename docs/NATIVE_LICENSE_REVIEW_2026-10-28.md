# Native and runtime distribution review — 2026-10-28

## Distribution choice

The owner authorized publishing IdealPlayer and required component sources while preserving
Jellyfin FFmpeg and full VLC functionality. The application's own MIT license remains intact.
The combined full-feature binary is offered under GPL version 3, with upstream copyright and
license rights preserved. The Maven LGPL label for LibVLC alone does not describe its statically
linked GPL components. No closed-source compatibility claim is made for this build.

The GPL components checked include VLC's GPL modules, x264, DVD CSS/navigation/read and
libpostproc: their source headers permit version 2 or later. GPLv3 is the selected combined-work
version; for LGPL code the corresponding source and rebuild/relink mechanism are retained.
Jellyfin's GPLv3 declaration is preserved. FreeType uses its FTL option (including attribution),
TagLib its LGPL option, and Nettle/GMP their LGPLv3-compatible options. AndroidX/Media3,
OkHttp and the other Apache-2.0 components remain under their original terms. Apache-2.0
is compatible with the selected GPLv3 combined distribution; it is not relabeled as GPL-only.

## Inventory and provenance

- `third_party/licenses/component-notices/index.json`: all 215 resolved runtime coordinates,
  exact AAR/JAR/POM hashes, 212 Apache-2.0 declarations, one BSD-3-Clause, Jellyfin GPLv3,
  and LibVLC's upstream LGPL declaration. Embedded license/NOTICE files are also retained.
- `third_party/licenses/native-notices/index.json`: notices from all 63 current contrib source
  archives, including FreeType FTL, IJG, AOM/VPX patent texts, upstream authors and LLVM notices.
  This intentionally includes some build-only/source-test notices; inclusion does not assert linking.
- Corresponding source input staging includes 160 exact published source JARs, 52 metadata-only
  POM coordinates, the local full LibVLC source trees, Guava's empty listenablefuture placeholder
  (no classes), and Dagger's lint-only artifact (not application code). Each classification is recorded.
- Jellyfin's complete root, Media3 and FFmpeg submodule archives are pinned to the publication
  commits in `sources.lock.json`. FFmpeg is the 6.0.2-derived audio-decoder configuration, not an
  assumed stock 6.0.1. Build scripts/toolchain versions are preserved.
- AndroidX graphics-path 1.0.1 and datastore-core-android 1.2.1 include exact Maven source JARs,
  pinned upstream native module archives and original C/C++ copyright headers. Graphics native
  code is path geometry, not a media/network decoder; DataStore uses local mmap/atomic primitives.
- libc++ and compiler-runtime notices from both actual NDK releases are retained. These are
  standard compiler/platform libraries, with source available in the named NDK/LLVM upstream
  releases, rather than undisclosed application-specific libraries. Proprietary SDK/toolchains and
  private signing keys are not included in the source archive.

This establishes publication provenance and build inputs. Published Jellyfin/AndroidX binaries
were not rebuilt bit-for-bit locally; identical binary hashes are not asserted. All local VLC
patches and exact binary/source identities are recorded. A notice filename inventory by itself
is not used as proof of license compliance: the combined distribution choice, original notices,
source availability and ability to build modified copies are separate requirements.

## Security review boundary

The exact native owners are AndroidX DataStore, AndroidX graphics-path, Jellyfin FFmpeg and
LibVLC. The existing 215-coordinate Maven advisory report is retained; it is not a complete
embedded-C/C++ vulnerability scan. Known enabled VLC FFmpeg issues have fourteen pinned
fixes. TLS, SFTP, SMB/NFS, PNG/XML and AOM fixes and their targeted evidence are detailed in
[VLC_TLS_RELEASE_2026-10-28.md](VLC_TLS_RELEASE_2026-10-28.md).

Applicability distinctions retained during review:

- Jellyfin enables the specified audio decoders and swresample, with no avformat demuxers,
  video/Vulkan paths or AAC encoder. The retained FFmpeg ancestry includes the earlier relevant
  swresample/thread fixes; VLC's newer video/demux fixes do not automatically apply to this build.
- Libarchive ZIP writer functions implicated by CVE-2026-45308 are absent from the linked DSO;
  VLC uses the reader side. The earlier CVE-2025-5915 fix predates selected 3.8.5.
- FreeType 2.13.1 already contains the fix for CVE-2025-27363. VPX 1.15.2 contains its recorded
  CVE-2025-5283 fix. Version age alone is not treated as proof of vulnerability.
- The application does not start UPnP discovery, expose VLC server controls or accept arbitrary
  VLC command-line options. Imported M3U URLs reject upnp/smb/nfs schemes. This bounds the
  normal app entry points; it does not prove all embedded network code is impossible to reach
  through every future API or nested playlist. Do not reuse this review for a discovery/server feature.
- The SOFA path is a fixed packaged default or the `hrtf-file` option; IdealPlayer does not expose
  that option or forward arbitrary playlist options. Source-only SOFA test datasets retain their
  separate terms and are not copied into application assets.
- GnuTLS uses its included libtasn1. A standalone source archive version is not sufficient evidence
  that that parser is linked into this TLS stack. Unsupported libssh2 ETM/GCM/EXT_INFO and
  unlinked public-key-list code are distinguished from the present, patched SFTP path.

This is a scoped engineering release review, not a guarantee of zero vulnerabilities or an
independent legal opinion. The retained older Libgcrypt and Live555 branches require continued
maintenance; the review must be reopened when dependencies, exposed options or modules change.

## Source delivery and installation

The release source package must be public **before** binary distribution. It contains the complete
application snapshot, native source archives, all patches, 63 contrib archives, Maven sources,
original notices, four-ABI configurations and instructions for building/replacing libraries.
`DISTRIBUTION.txt` points users to the release source location. Source retrieval requires no
account or payment. Keep it available as long as corresponding binaries are distributed.

Users can rebuild the application/native libraries, replace the selected local AAR and use their
own signing key. No integrity/Play-origin/signature gate is added. Android prevents an APK signed
with another key from updating an existing installation; use a separate application ID or export
user data before replacing an installation. Original user data and signing keys are never included.

References: [GPLv3, especially sections 4–6](https://www.gnu.org/licenses/gpl-3.0.html),
[LGPLv2.1, especially sections 3–6](https://www.gnu.org/licenses/lgpl-2.1.html).
Publication verification and final artifact identities are separate release checkpoints.
