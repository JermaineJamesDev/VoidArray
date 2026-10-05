<p align="center">
  <img src="docs/logo.png" alt="VoidArray logo" width="96">
</p>

<h1 align="center">VoidArray</h1>

<p align="center">
  Send files and messages between your own devices over your local network.<br>
  No accounts, no cloud, no internet connection required.
</p>

<p align="center">
  <a href="https://github.com/JermaineJamesDev/VoidArray/actions/workflows/ci.yml"><img src="https://github.com/JermaineJamesDev/VoidArray/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="https://github.com/JermaineJamesDev/VoidArray/releases/latest"><img src="https://img.shields.io/github/v/release/JermaineJamesDev/VoidArray" alt="Latest release"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-blue" alt="License: Apache 2.0"></a>
</p>

## Features

- **Fully offline.** Devices find each other on the same Wi-Fi or Ethernet network, or a phone hotspot.
  Nothing leaves your network.
- **Encrypted transfers.** Every connection uses TLS, and devices are identified by their own key rather
  than by name or IP address.
- **Pair by QR code.** Show a code on one device and scan it with your phone: no IP addresses to type and
  no codes to compare. Typing an IP address still works when there is no camera.
- **Pairing you can check.** Without a QR code, the first time two devices meet both show the same
  six-digit code. Trust a device once and later transfers can be accepted automatically (programs always
  ask).
- **Files, text, and links.** Send any number of files, a message, or a link. Links open with one tap.
- **Resumable.** If Wi-Fi drops mid-transfer, sending the same files again continues where it stopped.
- **Nothing is written until you accept**, and existing files are never overwritten.
- **Android:** share to VoidArray from any app; save received files to any folder you choose.
- **Windows:** drag and drop files onto the window; keeps running in the system tray so your PC stays
  reachable.

## Screenshots

<p align="center">
  <img src="docs/screenshots/send-dark.png" alt="Sending files on desktop" width="100%">
</p>
<p align="center">
  <img src="docs/screenshots/receive-dark-phone.png" alt="Receive screen on a phone" width="24%">
  <img src="docs/screenshots/offer-dark-phone.png" alt="Accepting files from a new device with a pairing code" width="24%">
  <img src="docs/screenshots/send-light-phone.png" alt="Send screen in the light theme" width="24%">
</p>

## Download

Get the latest build from the [Releases page](https://github.com/JermaineJamesDev/VoidArray/releases/latest).

| Platform | File | Notes |
| --- | --- | --- |
| Android 8.0+ | `VoidArray-X.Y.Z.apk` | Allow installing apps from your browser or file manager when prompted. |
| Windows 10/11 | `VoidArray-X.Y.Z-setup.exe` | Recommended. A standard installer with a Start Menu shortcut. Settings and keys go in your user profile. |
| Windows 10/11 | `VoidArray-X.Y.Z.msi` | The same app as an MSI, for managed or scripted installs. |
| Windows 10/11 | `VoidArray-X.Y.Z-portable.zip` | No install: unzip anywhere and run `VoidArray.exe`. Read the warning below first. |

> [!WARNING]
> **The portable build keeps its private key in a `data` folder beside the executable.** On a USB drive,
> and on any drive formatted FAT32 or exFAT, that folder has no access protection: anyone who holds or
> copies the drive gets the key and can pose as that install to every device that trusts it, including
> having transfers to it accepted automatically. Use the installer on any PC you own. If you use the
> portable build, keep the drive with you, don't turn on auto-accept for it on your other devices, and if
> the drive is lost, forget that install under **Settings > Security** on your other devices.

Each file has a matching `.sha256` checksum, and the APK a `.cert.txt` with its signing certificate. Every
file also has a signed build provenance attestation, so you can confirm it was built from this repository
with `gh attestation verify <file> --repo JermaineJamesDev/VoidArray`; see
[SECURITY.md](SECURITY.md#verifying-a-download). The Windows builds are not code-signed yet, so SmartScreen
may warn on first run; choose **More info > Run anyway**.

### First run on Windows

Allow VoidArray through the firewall when Windows asks, and make sure your network is set to **Private**.
Windows blocks device discovery on Public networks, and VoidArray shows a warning when that is the case.
To add the firewall rules ahead of time, run
[add-firewall-rules.ps1](desktopApp/packaging/windows/add-firewall-rules.ps1) as administrator.

## How it works

| Piece | Detail |
| --- | --- |
| Discovery | UDP multicast on `224.0.0.168:53318` plus directed broadcast. A device is listed only after a TLS exchange pinned to the key it announced. **Scan** probes the local /24 as a fallback, and devices can be paired by QR code or added by IP address. A device can be hidden from discovery in Settings. |
| Transport | HTTPS (TLS 1.2/1.3) on TCP port 53318, served by a small built-in HTTP/1.1 server (Ktor's CIO server cannot serve TLS). Only local-network addresses are accepted. |
| Identity | Each install generates a self-signed certificate. Peers pin the SHA-256 of its public key (SPKI), and both sides present their certificate (mutual TLS), so the receiver knows which key every request came from. On Android the key is kept in the Android Keystore. |
| QR pairing | The code holds the device's key fingerprint, its local addresses and a single-use token that expires in five minutes. The scanner pins the key from the code and presents the token; the showing device's user approves, and both devices then trust each other. |
| Pairing code | Derived from both keys and a random value from each side. The sender commits to its value before seeing the receiver's, so a man in the middle cannot make the codes match. |
| Storage | Incoming files are written to hidden partial files and renamed when complete, which is also what makes resume possible. |

See [SECURITY.md](SECURITY.md) for the threat model and how to report vulnerabilities.

## Building from source

Requirements: JDK 21 and the Android SDK with **Platform 37** (Android Studio Rabbit or newer opens the
project directly).

```bash
./gradlew :desktopApp:run                 # run the desktop app
./gradlew :androidApp:assembleDebug       # build a debug APK
./gradlew :engine:test                    # transfer, TLS pinning, trust and resume tests
./gradlew :desktopApp:packageExe          # build the Windows EXE installer
./gradlew :desktopApp:packageMsi          # build the Windows MSI installer
./gradlew :desktopApp:packagePortableZip  # build the portable ZIP
```

### Project layout

| Module | What it holds |
| --- | --- |
| [core](core) | Multiplatform protocol models and the `TransferController` contract the UI talks to. |
| [engine](engine) | Plain Kotlin/JVM: HTTPS server and client, discovery, TLS identity, trust store, history. Used by both apps. |
| [shared](shared) | Compose Multiplatform UI. |
| [androidApp](androidApp) | Android entry point, storage-access-framework file handling, permissions, foreground service. |
| [desktopApp](desktopApp) | Desktop entry point, tray, drag and drop, Windows network-profile check, installers. |

The engine is a JVM module rather than a source set shared between the JVM and Android targets because
Kotlin does not support that combination: Gradle compiles it, but the IDE cannot resolve it.

## Contributing

Contributions are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md). Maintainers publishing a release should
follow [docs/RELEASING.md](docs/RELEASING.md).

## License

VoidArray is licensed under the [Apache License 2.0](LICENSE). See [NOTICE](NOTICE) for third-party
attributions; the bundled Cinzel and IBM Plex fonts are under the SIL Open Font License
([Cinzel](licenses/OFL-Cinzel.txt), [IBM Plex](licenses/OFL-IBM-Plex.txt)).
