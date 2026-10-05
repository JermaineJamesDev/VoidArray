# Security policy

## Reporting a vulnerability

Please report vulnerabilities privately through
[GitHub's private vulnerability reporting](https://github.com/JermaineJamesDev/VoidArray/security/advisories/new)
rather than in a public issue. Include the affected version, platform, and steps to reproduce.

- You should get a first response within a week.
- We aim to release a fix within 90 days of the report, sooner for issues that are easy to exploit, and
  will agree a disclosure date with you. Please do not disclose the issue publicly before then.
- You will be credited in the advisory unless you ask not to be.
- Only the latest release receives security fixes.

### In scope

The Android and Windows apps, the network protocol between them, and the release workflow that builds and
signs them.

### Out of scope

Problems that need a device that is already compromised or unlocked in an attacker's hands, denial of
service from a device on the same network (see below), and reports from automated scanners without a
working proof of concept.

## Threat model

VoidArray is designed for transfers between your own devices on a local network you mostly trust. It aims
to protect against:

- **Eavesdropping** on the local network: all transfers use TLS 1.2 or 1.3.
- **Impersonation of a known device:** devices are identified by the SHA-256 of their public key (SPKI).
  Both sides authenticate with that key in the TLS handshake (mutual TLS): the sender pins the receiver's
  key, and the receiver only accepts an offer whose claimed identity is the key the sender actually
  presented. Upload and cancel requests are bound to that key. A trusted device that presents a different
  key is flagged and cannot be sent to until the user reviews it.
- **A first-contact man-in-the-middle, when pairing by QR code.** The code carries the showing device's
  key fingerprint, its local addresses, and a random single-use token. The scanning device pins the key
  from the code, so it can only complete a connection to the device that showed it, and presents the token
  to prove it saw the screen. The showing device's user still approves the scanning device by name before
  either side trusts the other. A code expires after five minutes and works once. Addresses in a code must
  be literal local-network IPs, so a crafted code cannot send the scanner to the internet or a host name.
- **A first-contact man-in-the-middle, when users compare the six-digit pairing code.** The code is
  derived from both keys and a fresh random value from each side, and the sender commits to its value
  before it learns the receiver's. An attacker relaying between the two devices therefore cannot choose
  keys or values that make the codes match; each attempt is a one-in-a-million guess, and pairing attempts
  are rate limited. A new device can only be remembered as trusted by ticking "Codes match".
- **Spoofed discovery:** announcements are unauthenticated UDP, so a device is only listed after a TLS
  exchange pinned to the key it announced. A second key claiming the id of an active device does not
  replace it. Announcements are rate limited per source.
- **Reaching a device from outside the local network:** connections and discovery packets are only
  accepted from private, link-local or on-subnet addresses, even when the device has a globally routable
  IPv6 address.
- **Unwanted files:** nothing is written until the user accepts, unless they enabled auto-accept for a
  device they explicitly trusted. Offers that include programs (such as `.exe`, `.msi`, `.apk` or scripts)
  say so and always need the user to accept, even from a trusted device with auto-accept on. Received file
  and device names are sanitized so they cannot escape the chosen folder or hide their real extension with
  direction-override characters, and existing files are never overwritten. Accept only becomes active a
  moment after an offer appears, so a tap meant for something else cannot accept it.
- **Malformed network input:** the built-in HTTP server enforces limits on header size, header count,
  request size, and concurrent connections overall and per address.
- **Copying the identity off the device:**
  - On Android the private key lives in the Android Keystore, in secure hardware where the device has it,
    and cannot be read out, even by VoidArray itself. A key from an older version is imported once (keeping
    its fingerprint, so pairings survive) and its file is deleted. If the Keystore refuses the key, which
    some devices may, VoidArray falls back to the app-private file and tries again on the next launch. The
    device id is excluded from Android backups and device-to-device transfer, and other apps cannot get
    VoidArray to send its own private files through the share sheet.
  - On Windows the installed app keeps its key in a password-protected PKCS12 file in the user's
    `%APPDATA%\VoidArray` folder, which other user accounts cannot read. The password sits next to it, so
    this protects against other users, not against software running as you or someone with the disk.
  - On systems with POSIX permissions the key and settings files are readable only by their owner.
- **A key that may have been copied:** Settings > Security > Show details > Reset key creates a new key.
  Devices that trusted the old one then show "Key changed" and stop sending to this device until they pair
  again.

It does not protect against:

- A first-contact man-in-the-middle **if users skip comparing the six-digit pairing code** shown on both
  devices and accept anyway. Pairing by QR code avoids this.
- Someone who photographs a pairing QR code and scans it from another device before you do. They still
  need you to approve their device by name, and the code is single-use, so your own scan would fail.
- Other devices on the network learning that a VoidArray device exists and seeing its name, which
  discovery announces. Turning off **Visible to nearby devices** stops this device from announcing itself
  or answering announcements, but a device that probes every address on the subnet can still find it.
- Denial of service by a device on the same network, such as flooding the discovery port or using up the
  pairing rate limit.
- A compromised device that you have already trusted, beyond the program rule above. Forget it under
  Settings > Security, or reset this device's key.
- Malware running as your user on Windows, which can read the key file.
- Someone with access to the files of the **portable Windows build**. Its key sits in the `data` folder
  beside the executable, and drives formatted FAT32 or exFAT have no file permissions, so anyone holding the
  drive can copy the key and impersonate that install to devices that trust it. If such a drive is lost,
  reset the key on a copy you still have, or forget that install on your other devices.

## Verifying a download

Every release file has a `.sha256` checksum, and the Android APK has a `.cert.txt` file with its signing
certificate's SHA-256 digest. Release files built by GitHub Actions also carry a signed build provenance
attestation, which proves they were built from this repository by the release workflow:

```bash
gh attestation verify VoidArray-X.Y.Z.apk --repo JermaineJamesDev/VoidArray
```

The APK signing certificate never changes between releases. To check it yourself, run
`apksigner verify --print-certs VoidArray-X.Y.Z.apk` (from the Android SDK build tools) and compare the
SHA-256 digest with the `.cert.txt` file of the release you first installed. Android also refuses to
install an update signed with a different certificate over an existing install.

The Windows installers and executable are not code-signed yet, so Windows SmartScreen may warn about them;
use the attestation or checksum to confirm a download instead.
