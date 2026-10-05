# Contributing to VoidArray

Thanks for your interest in improving VoidArray. Bug reports, fixes, and focused feature work are all
welcome.

## Before you start

- For anything larger than a small fix, open an issue first so the approach can be agreed before you
  spend time on it.
- Security problems should not be reported in public issues; see [SECURITY.md](SECURITY.md).

## Development setup

- JDK 21 and the Android SDK with Platform 37. Android Studio Rabbit or newer opens the project directly.
- Run the desktop app with `./gradlew :desktopApp:run`; build Android with `./gradlew :androidApp:assembleDebug`.
- Testing a transfer needs two devices (or a phone and the desktop app) on the same network.

See the project layout in the [README](README.md#project-layout) to find where code belongs. Networking and
transfer logic go in `engine`, UI in `shared`, and platform glue in `androidApp` or `desktopApp`.

## Making changes

- Follow the [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
  (`kotlin.code.style=official`).
- Keep pull requests focused on one change, without unrelated reformatting.
- Write comments that explain why, not what; the code should say what it does.
- Add or update tests in `engine/src/test` for any change to the protocol, transfer, storage, or security
  code. These run over real HTTPS on loopback.
- Breaking changes to the wire protocol must bump `PROTOCOL_VERSION` in `core/.../protocol/Protocol.kt`,
  since mixed versions cannot interoperate. A new optional route can be added without a bump if older
  peers answering 404 is handled with a clear message, as QR pairing does.
- New dependencies should be discussed in the issue or pull request first.

## UI changes and screenshots

`ScreenshotRenderer` renders the real UI with sample data, without a device or window, to
`shared/build/screenshots`:

```bash
./gradlew :shared:jvmTest --tests '*ScreenshotRenderer*' -Pscreenshots=true
```

Attach before and after images to UI pull requests. Copy updated images into `docs/screenshots` when the
README ones go stale.

## Before opening a pull request

Run the same checks as CI:

```bash
./gradlew :engine:test :shared:jvmTest :desktopApp:compileKotlin :androidApp:assembleDebug
```

Describe what changed and how you tested it, including which platforms you tried.

## License

By contributing, you agree that your contributions are licensed under the [Apache License 2.0](LICENSE).
