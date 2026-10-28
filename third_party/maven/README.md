# Locally rebuilt LibVLC artifact

This Maven-layout directory contains the exact LibVLC AAR and POM selected by the current working
tree:

`org.videolan.android:libvlc-all:3.7.2-idealplayer-remediation.2`

Gradle verifies both files by SHA-256 during settings evaluation and refuses to fall back to a
remote artifact when the local files are missing or modified. The AAR was built from the pinned
LibVLC/JNI/VLC sources and FFmpeg 8.1.2 recipe in `third_party/native/`, with four ABI outputs and
eleven upstream FFmpeg security patches. Build and runtime evidence is retained under
`build/native-remediation-2026-10-04/`.

This is immutable local build input for review. It is not a publication, a source offer, a license
clearance or a claim that all linked native components have been inventoried. The corresponding
source/build and relinking delivery package is staged separately and still requires legal/security
review before any release or distribution decision.
