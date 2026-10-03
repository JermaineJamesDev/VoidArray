# Security policy

## Reporting a vulnerability

Please report vulnerabilities privately through
[GitHub's private vulnerability reporting](https://github.com/JermaineJamesDev/VoidArray/security/advisories/new)
rather than in a public issue. Include the affected version, platform, and steps to reproduce. You should
get a response within a week.

Only the latest release receives security fixes.

## Threat model

VoidArray is designed for transfers between your own devices on a local network you mostly trust. It aims
to protect against:

- **Eavesdropping** on the local network: all transfers use TLS 1.2 or 1.3.
- **Impersonation of a known device:** devices are identified by the SHA-256 of their public key (SPKI).
  Both sides authenticate with that key in the TLS handshake (mutual TLS): the sender pins the receiver's
  key, and the receiver only accepts an offer whose claimed identity is the key the sender actually
  presented. Upload and cancel requests are bound to that key. A trusted device that presents a different
  key is flagged and cannot be sent to until the user reviews it.
- **A first-contact man-in-the-middle, when users compare the six-digit pairing code.** The code is
  derived from both keys and a fresh random value from each side, and the sender commits to its value
  before it learns the receiver's. An attacker relaying between the two devices therefore cannot choose
  keys or values that make the codes match; each attempt is a one-in-a-million guess, and pairing attempts
  are rate limited.
- **Spoofed discovery:** announcements are unauthenticated UDP, so a device is only listed after a TLS
  exchange pinned to the key it announced. A second key claiming the id of an active device does not
  replace it. Announcements are rate limited per source.
- **Reaching a device from outside the local network:** connections and discovery packets are only
  accepted from private, link-local or on-subnet addresses, even when the device has a globally routable
  IPv6 address.
- **Unwanted files:** nothing is written until the user accepts, unless they enabled auto-accept for a
  device they explicitly trusted. Received file and device names are sanitized so they cannot escape the
  chosen folder or hide their real extension with direction-override characters, existing files are never
  overwritten, and offers that include programs say so. Accept only becomes active a moment after an offer
  appears, so a tap meant for something else cannot accept it.
- **Malformed network input:** the built-in HTTP server enforces limits on header size, header count,
  request size, and concurrent connections overall and per address.
- **Copying the identity off the device:** the key is excluded from Android backups and device-to-device
  transfer, other apps cannot get VoidArray to send its own private files through the share sheet, and on
  systems with POSIX permissions the key and settings files are readable only by their owner.

It does not protect against:

- A first-contact man-in-the-middle **if users skip comparing the six-digit pairing code** shown on both
  devices.
- Other devices on the network learning that a VoidArray device exists and seeing its name, which
  discovery announces.
- Denial of service by a device on the same network, such as flooding the discovery port or using up the
  pairing rate limit.
- A compromised device that you have already trusted.
- Someone with access to the files of the portable Windows build. Its key sits in the `data` folder beside
  the executable, and drives formatted FAT32 or exFAT have no file permissions, so anyone holding the drive
  can copy the key and impersonate that install to devices that trust it.
