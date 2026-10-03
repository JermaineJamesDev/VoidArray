# EzTransfer

Offline file and message transfer between your own devices on the same local network. Android and
Windows desktop apps built with Kotlin Multiplatform and Compose Multiplatform.

## Modules

| Module | What it holds |
| --- | --- |
| [core](./core) | Multiplatform protocol models and the `TransferController` contract the UI talks to. |
| [engine](./engine) | Plain Kotlin/JVM: HTTPS server and client, discovery, TLS identity, trust store, history. Used by both apps. |
| [shared](./shared) | Compose Multiplatform UI (`App`). |
| [androidApp](./androidApp) | Android entry point, storage-access-framework file handling, permission gate, foreground service. |
| [desktopApp](./desktopApp) | Desktop entry point, tray, drag and drop, Windows network-profile check, MSI packaging. |

The engine is a JVM module rather than a source set shared between the JVM and Android targets because
Kotlin does not support that combination; Gradle compiles it, but the IDE cannot resolve it.

## How it works

- **Discovery:** UDP multicast on `224.0.0.168:53318` plus directed broadcast, with an HTTPS sweep of the
  local /24 as a fallback ("Scan"). Devices can also be added by IP.
- **Transport:** HTTPS on TCP port 53318 (an ephemeral port if that is taken), served by a small built-in
  HTTP/1.1 server because Ktor's CIO server cannot serve TLS. Received files are written to hidden partial
  files and renamed when complete; re-sending the same files resumes from the partial.
- **Identity and pairing:** each install generates a self-signed certificate. Peers are identified by the
  SHA-256 of its public key (SPKI) and every data request pins that key. A receiver verifies a sender by
  calling back to the sender's own server with the claimed key pinned. New devices show a six-digit pairing
  code on both screens; ticking "Trust this device" allows auto-accept later (Settings).

## Running

- Android: `./gradlew :androidApp:assembleDebug` (compileSdk and targetSdk 37; needs SDK Platform 37).
- Desktop: `./gradlew :desktopApp:run`, or `./gradlew :desktopApp:hotRun --auto` for Compose Hot Reload.
- Tests: `./gradlew :engine:test` runs the transfer, TLS pinning, trust, and resume tests over loopback.

## Windows packaging

`./gradlew :desktopApp:packageMsi` builds the installer. jpackage cannot add firewall rules, so run
[add-firewall-rules.ps1](./desktopApp/packaging/windows/add-firewall-rules.ps1) as administrator after
installing (or from an installer wrapper such as Inno Setup):

```powershell
.\add-firewall-rules.ps1 -ProgramPath "C:\Program Files\EzTransfer\EzTransfer.exe"
```

Windows blocks discovery on networks marked Public; the app shows a warning naming the adapter when
that is the case.
