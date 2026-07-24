# Privacy notes

This document describes the repository's default behavior. A distributor must adapt it into a
jurisdiction-appropriate privacy policy and ensure the Play Data safety form matches the exact
build and hosted services being released.

## Data stored on the device

IdealPlayer can store user-entered playlist URLs, local document URIs, Xtream server addresses,
usernames and passwords, EPG URLs, watch history, favorites, settings, cached metadata, and a
salted parental PIN hash. These values remain in app-private storage. Android cloud backup and
device-to-device transfer are disabled for the app.

The Room database is not SQLCipher-encrypted. Android's application sandbox protects it during
normal operation, but a compromised/rooted device can weaken that protection. The parental PIN
hash is stored through Android Keystore-backed encrypted preferences.

## Network connections

Depending on user configuration, the app connects to:

- playlist, stream, image, Xtream, and XMLTV endpoints supplied by the user;
- TMDB endpoints when the distributor configured a TMDB key and metadata lookup is used;
- a distributor-operated remote-setup API when that optional feature was configured;
- a distributor-operated update manifest/APK host in `selfHostedRelease` builds.

The repository includes no analytics SDK, advertising SDK, default IPTV provider, public playlist
directory, or default EPG feed. Normal Play-oriented release builds do not download or install APK
updates.

Some user-provided IPTV services only support HTTP. Cleartext traffic is therefore permitted, but
HTTP does not protect credentials or media URLs in transit. Users should prefer HTTPS endpoints.

## Logging

Debug builds write diagnostic logs. URL logging is redacted and Xtream request failures omit
exception objects that may retain credential-bearing URLs. Release builds do not install a Timber
logging tree. Distributors should not add network body logging or upload logs without explicit
consent and additional redaction.

## Optional remote setup

Remote setup is disabled unless a distributor provides both HTTPS build settings. Pairing payloads
can contain playlist URLs and Xtream credentials. The backend operator must publish its identity,
purpose, retention period, deletion process, security controls, and contact method. Pairings should
expire within ten minutes and be deleted immediately after retrieval.

## User controls

Users can edit or delete playlists and clear watch history from the application. Uninstalling the
app removes its app-private data under normal Android behavior.
