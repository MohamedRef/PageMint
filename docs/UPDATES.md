# Updating PageMint through Preview releases

PageMint checks **only published GitHub Pre-releases (Preview channel)** for a signed
`PageMint-release.apk` when the user taps **Preview updates** in the app header.
It ignores stable releases, draft releases and malformed version tags. The latest
eligible Preview is chosen by Android versionCode, not publication timestamp.
GitHub's `/releases/latest` excludes Pre-releases; the app uses `/releases?per_page=30` instead.

## On-device behavior

1. The user selects **Preview updates**. PageMint retrieves recent GitHub Releases
   and selects the newest published **Pre-release** only.
2. It compares Android `versionCode` with the installed version.
3. For a newer version, it downloads the exact release APK over HTTPS.
4. It verifies the GitHub SHA-256 asset digest, file size, application ID,
   `versionCode` and (Android 9+) that the APK has the **same signing certificate**
   as the installed app. Android also verifies the signature during installation.
5. It opens the Android package installer with a temporary, read-only
   FileProvider URI. Android asks the user to confirm installation. On Android
   8+ the user may have to allow PageMint to request installs from this source.

No broad filesystem access is required and the app never silently installs an
APK. Internet access is used only for the user-requested update check/download;
document processing remains entirely offline.

## One-time signing setup

**Never commit a keystore, passwords, or private keys to the repository.**
Generate a persistent signing key locally and back it up safely:

```bash
keytool -genkeypair -v \
  -keystore pagemint-release.jks \
  -alias pagemint \
  -keyalg RSA -keysize 3072 \
  -validity 10000
```

The tool asks for a keystore password and certificate details.
Choose and keep a stable key alias and key password (they may be the same as
the store password).

Open the GitHub repository **Settings → Secrets and variables → Actions** and
create four repository secrets:

| Secret | Value |
|---|---|
| `PAGEMINT_KEYSTORE_BASE64` | Base64 of the complete `.jks` file, without line breaks |
| `PAGEMINT_KEYSTORE_PASSWORD` | Keystore password |
| `PAGEMINT_KEY_ALIAS` | Alias used with `keytool` |
| `PAGEMINT_KEY_PASSWORD` | Private-key password |

Generate the keystore secret locally:

```bash
# Linux:
base64 -w 0 pagemint-release.jks
# macOS:
base64 < pagemint-release.jks | tr -d '\n'
```

Copy the base64 directly into GitHub's encrypted secret field; do not paste it
into issues, PRs, messages, or code. Keep a private offline backup of the key.

## Publish a Preview update

Open **Actions → Publish signed PageMint Preview → Run workflow** on the `main`
branch. Enter `0.1.1` the first time, then `0.1.2`, `0.1.3` and so on.
You can also push a `vMAJOR.MINOR.PATCH` tag.

The workflow runs Rust tests, compiles the native Android libraries, runs
Android JVM tests, builds and verifies the signed release APK, and publishes
the `PageMint-release.apk` and its `.sha256` to GitHub Releases **with the
GitHub Pre-release flag enabled** (`--prerelease`), titled `PageMint 0.1.1 Preview`.
It never publishes this workflow's artifacts as a Stable Release.
The Git tag is still `v0.1.1`; it is the GitHub **Pre-release flag**, not a
suffix on the tag, that selects this update channel. Android's versionName is
`0.1.1-preview`.
It **fails without the four signing secrets** and refuses to overwrite an
existing GitHub release asset.

Version mapping: `versionCode = MAJOR*1_000_000 + MINOR*1_000 + PATCH`
(MINOR and PATCH must be between 0 and 999). All new versions must have
larger codes. The app uses the same mapping when comparing releases.

## Important: previous debug APKs

The existing debug APK artifacts were signed by an automatically generated
Android debug key. **They are not guaranteed to share a signing certificate
with each other or with a properly signed release.** Android won't accept an
in-place update if signatures differ. Export any needed scans and install the
first permanently signed `PageMint-release.apk` **once** (which may require
uninstalling the debug build and discards local app data).

From that signed release onward, all future versions published using this
unchanged keystore install in place, preserving app data, subject to Android's
normal installer confirmation. Do **not** rotate or lose this signing key.

The GitHub Actions **debug APK artifact** is for development, not an update
channel. Only publish signed **GitHub Pre-release APKs** to the updater.

## Limitations

- This updater intentionally only supports the public `MohamedRef/PageMint`
  repository and a single fixed release asset name; no arbitrary APK URL input.
- It does not run automatically in the background. Checking is user-initiated.
- Previews are experimental and may contain bugs. The updater does not select
  ordinary Stable Releases, even if they were published more recently.
- It does not use Google Play's in-app updates API.
- GitHub must be reachable for checks/downloads.
- On a debug APK with a different key, the updater warns rather than trying
  to bypass Android's signature security.
- Release signing and device installs must still be verified in practice before
  calling this production-ready.
