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
- **Pairing you can check.** The first time two devices meet, both show the same six-digit code. Trust a
  device once and later transfers can be accepted automatically.
- **Files, text, and links.** Send any number of files, a message, or a link. Links open with one tap.
- **Resumable.** If Wi-Fi drops mid-transfer, sending the same files again continues where it stopped.
- **Nothing is written until you accept**, and existing files are never overwritten.
- **Android:** share to VoidArray from any app; save received files to any folder you choose.
- **Windows:** drag and drop files onto the window; keeps running in the system tray so your PC stays
  reachable.

## Download

Get the latest build from the [Releases page](https://github.com/JermaineJamesDev/VoidArray/releases/latest).

| Platform | File | Notes |
| --- | --- | --- |
| Android 8.0+ | `VoidArray-X.Y.Z.apk` | Allow installing apps from your browser or file manager when prompted. |
| Windows 10/11 | `VoidArray-X.Y.Z-setup.exe` | Standard installer. |
| Windows 10/11 | `VoidArray-X.Y.Z.msi` | For managed or scripted installs. |

Each file has a matching `.sha256` checksum. The Windows installers are not code-signed yet, so
SmartScreen may warn on first run; choose **More info > Run anyway**.

### First run on Windows

Allow VoidArray through the firewall when Windows asks, and make sure your network is set to **Private**.
Windows blocks device discovery on Public networks, and VoidArray shows a warning when that is the case.
To add the firewall rules ahead of time, run
[add-firewall-rules.ps1](desktopApp/packaging/windows/add-firewall-rules.ps1) as administrator.

## How it works

| Piece | Detail |
| --- | --- |
| Discovery | UDP multicast on `224.0.0.168:53318` plus directed broadcast. **Scan** probes the local /24 as a fallback, and devices can be added by IP address. |
| Transport | HTTPS on TCP port 53318, served by a small built-in HTTP/1.1 server (Ktor's CIO server cannot serve TLS). |
| Identity | Each install generates a self-signed certificate. Peers pin the SHA-256 of its public key (SPKI) on every request that carries data. |
| Sender verification | A receiver calls back to the sender's own server with the claimed key pinned, proving the sender holds that key before it can be treated as trusted. |
| Storage | Incoming files are written to hidden partial files and renamed when complete, which is also what makes resume possible. |

See [SECURITY.md](SECURITY.md) for the threat model and how to report vulnerabilities.

## Building from source

Requirements: JDK 21 and the Android SDK with **Platform 37** (Android Studio Rabbit or newer opens the
project directly).

```bash
./gradlew :desktopApp:run                 # run the desktop app
./gradlew :androidApp:assembleDebug       # build a debug APK
./gradlew :engine:test                    # transfer, TLS pinning, trust and resume tests
./gradlew :desktopApp:packageExe          # build a Windows installer locally
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
attributions.
