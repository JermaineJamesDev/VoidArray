# Releasing

Publishing a GitHub release runs [release.yml](../.github/workflows/release.yml), which builds the signed
Android APK and the Windows EXE and MSI installers and attaches them, with SHA-256 checksums, to the
release.

## One-time setup: Android signing key

Android only installs an update if it is signed with the same key as the installed version, so this key
must be created once and kept forever.

1. Create the keystore (use a strong password and keep it in a password manager):

   ```bash
   keytool -genkeypair -v -keystore voidarray-release.jks -alias voidarray -keyalg RSA -keysize 4096 -validity 10000
   ```

2. **Back up `voidarray-release.jks` and its passwords somewhere safe outside the repository.** If the key
   is lost, existing users cannot update and must uninstall first. `*.jks` is already in `.gitignore`.

3. Base64-encode it for GitHub:

   ```powershell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("voidarray-release.jks")) | Set-Clipboard
   ```

   or on Linux/macOS: `base64 -w0 voidarray-release.jks`.

4. In the repository, open **Settings > Secrets and variables > Actions** and add:

   | Secret | Value |
   | --- | --- |
   | `ANDROID_KEYSTORE_BASE64` | The base64 text from step 3 |
   | `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
   | `ANDROID_KEY_ALIAS` | `voidarray` (or the alias you chose) |
   | `ANDROID_KEY_PASSWORD` | Key password (the same as the keystore password unless you set a different one) |

## Publishing a release

1. Make sure CI is green on `main`.
2. Create a release on GitHub with a new tag named `vX.Y.Z`, for example `v1.2.0`.
3. Publish it. The workflow attaches these files within a few minutes:
   - `VoidArray-X.Y.Z.apk`
   - `VoidArray-X.Y.Z-setup.exe`
   - `VoidArray-X.Y.Z.msi`
   - `VoidArray-X.Y.Z-portable.zip`
   - a `.sha256` file for each

### Version rules

- Tags must be `vX.Y.Z` with numbers only; pre-release suffixes are rejected because Windows installers
  require a purely numeric version.
- The Android version code is `X*10000 + Y*100 + Z`, so `Y` and `Z` must stay below 100, and every
  release must have a higher version than the last.
- Local builds use `appVersion` from `gradle.properties`; release builds override it from the tag.

## Testing the pipeline without releasing

Run the **Release** workflow manually from the Actions tab and enter a version. It builds everything and
uploads the files as workflow artifacts instead of attaching them to a release. The signing secrets are
still required.

## Signing locally

To produce a signed release APK on your own machine, set the same variables before building:

```powershell
$env:ANDROID_KEYSTORE_PATH = "C:\path\to\voidarray-release.jks"
$env:ANDROID_KEYSTORE_PASSWORD = "..."
$env:ANDROID_KEY_ALIAS = "voidarray"
$env:ANDROID_KEY_PASSWORD = "..."
./gradlew :androidApp:assembleRelease
```

Without them, `assembleRelease` produces an unsigned APK.
