# Native security release — 2026-10-28

Current selected candidate: `org.videolan.android:libvlc-all:3.7.2-idealplayer-remediation.8`.
AAR SHA-256: `9028ee526d11d132b5e77e1d7abe3a696d04d3dd4eba16fb8965820a5b316d13`.
This document supersedes the .5/.6/.7 candidate identities, not their retained test evidence.

## Changes and preserved behavior

- GnuTLS 3.8.13 and Nettle 3.10.2 replace 3.8.10/3.7.3, preserving the TLS feature configuration.
- libssh2 1.10.0 retains its features with the upstream SFTP handle double-free fix
  (`5e4776146552d898b9c0e1b313cd093fa8dc92d0`). The Clang 9 unused-attribute fallback is compiler compatibility only.
- Seven SMB/NFS upstream security fixes are backported without dropping network modules.
- libpng 1.6.59 replaces 1.6.53; libxml2 2.15.4 replaces 2.15.1. The Windows threads patch
  already integrated upstream is omitted; the other Windows compatibility patch remains.
- AOM 3.5.0 retains its decoder with the two upstream CVE-2024-5171 allocation overflow fixes.
- Libgcrypt remains 1.10.1 with T8211/CVE-2026-41989 backported. This is not a full Libgcrypt upgrade.
- Fourteen existing FFmpeg fixes, all VLC/FFmpeg feature flags, public VLC/JNI symbols, and
  Java `classes.jar` bytes are preserved. No codec, subtitle, protocol, or playback feature was removed.
- Reconfiguration normalizes FFmpeg `SRC_PATH` to its existing `src` symlink on 32-bit builds;
  verification resolves it to the same source tree and compares every other configuration line.
  Some dependent static archives were rebuilt (including libbluray); their identities are recorded,
  rather than claiming every archive is unchanged.

## Targeted evidence, without rerunning prior suites

All four ABIs compile and retain public API/configuration and 16 KB alignment for 64-bit ELFs.
`third_party/native/evidence/remediation-8-build.json` binds the selected AAR to those native bytes.

Physical TV (MiTV-AYFR0, API 34, armeabi-v7a):

| Candidate tested | New checks | Result |
|---|---|---|
| .6 | Valid trusted TLS 1.3/HTTP 200; reject hostname mismatch, expired/untrusted certificates, URI-only SAN/CN fallback and oversized SAN/CN fallback | 6 pass |
| .7 | SMB bounds, UTF-16 lengths, ACL offsets/iovec boundary; NFS bounded/preallocated/borrowed string/array decoding | pass |
| .8 | 10 PNG palette/transparency widths across NEON boundaries, exact pixels/output canaries, corrupt zTXt/truncated PNG; XML namespaces/entities/malformed rejection/QName dictionary; AOM dimension/alignment overflows and valid allocation/wrap | pass |

TLS checks directly call the verification API used by VLC from its actual DSO. Their temporary
CA never enters the Android trust store. The first valid-TLS diagnostic needed an E_AGAIN receive
loop for TLS 1.3; only that case was rerun after fixing the test helper. Negative cases were not repeated.
SMB/NFS and image/XML checks use the actual candidate DSO and synthetic in-memory/local data;
these are API-level device regressions, not end-to-end SMB/NFS server or codec playback tests.
Malformed PNG warnings are expected assertions, not unexplained failures.

The .8 build preserves the exact .7 GnuTLS/Nettle/Gcrypt/SSH/SMB/NFS static archives in each ABI.
Therefore the earlier targeted runtime evidence is retained with its actual candidate identity;
no claim is made that those device cases ran again against .8.

Previously passing 523 JVM, 18 TV navigation/focus, codec/caption/resume and long-soak suites
were not rerun, as requested. They remain earlier regression evidence, not certification of
all final source/runtime/device combinations.

## Sources

- [GnuTLS releases](https://www.gnutls.org/download.html)
- [libpng release and security notes](https://www.libpng.org/pub/png/libpng.html)
- [libxml2 2.15.4 release notes](https://download.gnome.org/sources/libxml2/2.15/libxml2-2.15.4.news)
- [AOM upstream backport retained by Debian](https://sources.debian.org/data/main/a/aom/3.6.0-1%2Bdeb12u2/debian/patches/CVE-2024-5171.patch)

Exact source archives, SHA-256/SHA-512 pins, local changes, upstream identifiers and evidence
are in `third_party/native/`. Original licenses and full source inputs must accompany distribution.

## Publication checkpoint

Version 43 / 1.2.9 is intended for Play; production was verified as 42 / 1.2.8.
The only current Play policy issue is the API 36 target requirement. Source targets API 36;
final artifact validation and Play's own upload checks must confirm it. Source publication,
final signed AAB identity and review/submission status are recorded separately; this document
alone does not claim a Play upload or a live release.
