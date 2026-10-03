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

- **Eavesdropping** on the local network: all transfers use TLS.
- **Impersonation of a known device:** devices are identified by the SHA-256 of their public key (SPKI),
  pinned on every request that carries data. A receiver verifies a sender by connecting back to the
  sender's own server with the claimed key pinned before treating it as trusted, and warns when a trusted
  device presents a different key.
- **Unwanted files:** nothing is written until the user accepts, unless they enabled auto-accept for a
  device they explicitly trusted. Received file names are sanitized so they cannot escape the chosen
  folder, and existing files are never overwritten.
- **Malformed network input:** the built-in HTTP server enforces limits on header size, header count,
  request size, and concurrent connections.

It does not protect against:

- A first-contact man-in-the-middle **if users skip comparing the six-digit pairing code** shown on both
  devices.
- Other devices on the network learning that a VoidArray device exists and seeing its name, which
  discovery announces.
- Denial of service by a device on the same network, such as flooding the discovery port.
- A compromised device that you have already trusted.
