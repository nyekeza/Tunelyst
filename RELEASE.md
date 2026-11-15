# Preparing a Tunelyst GitHub release

This repository is intended to stay private unless the owner deliberately changes its visibility. A release in a private repository is not a public download. Nothing in this guide publishes or pushes automatically until you choose to push a version tag.

## 1. Create and protect the release signing key

Create **one new release key** in a private location outside this project, on a trusted device with JDK `keytool`. For example, from a private home directory:

```sh
keytool -genkeypair -keystore ~/tunelyst-release.jks -storetype JKS -alias tunelyst -keyalg RSA -keysize 4096 -validity 10000
```

Enter the prompted passwords privately; do not paste passwords or the key into chat, a commit, or a public issue. Follow the prompts to create the certificate. Store a secure offline backup of the keystore, its alias, and both passwords. All later updates must use the **same signing key**; losing it can prevent users from installing updates over existing installations. Do not generate a fresh key for every release. Confirm the certificate SHA-256 fingerprint privately with:

```sh
keytool -list -v -keystore ~/tunelyst-release.jks -alias tunelyst
```

The key created for this release is unrelated to any disposable or debug key. Android will reject an update over an existing installation signed with another certificate. Uninstalling can remove app data (including local playlists/settings); preserve anything important and confirm a recovery method first. The same package ID also prevents installing the two variants side by side.

## 2. Configure GitHub Actions secrets

In the private GitHub repository, open **Settings → Secrets and variables → Actions → New repository secret** and set all four values:

| Secret | Value |
| --- | --- |
| `TUNELYST_KEYSTORE_BASE64` | The entire new keystore, base64 encoded as one line |
| `TUNELYST_STORE_PASSWORD` | Keystore password |
| `TUNELYST_KEY_ALIAS` | Alias selected above (`tunelyst` in the example) |
| `TUNELYST_KEY_PASSWORD` | Private key password |

If the GitHub CLI is installed and already authenticated with permission to manage this repository's secrets, the encoded key can be sent directly without printing it:

```sh
base64 < ~/tunelyst-release.jks | tr -d '\n' | gh secret set TUNELYST_KEYSTORE_BASE64 --repo nyekeza/Tunelyst
```

Set the other secrets through GitHub's UI or `gh secret set` with its interactive input. Never commit the key or `release.properties`; `.gitignore` does not remove files that Git already tracks. Check staged **and tracked** files for keys, credentials and other sensitive data before committing or pushing. Rotate any credential that was exposed; deleting a file or adding an ignore rule does not remove secrets from Git history. Do not paste secrets into terminal commands, build logs or chat. Restrict repository access: trusted workflows on pushed tags can access repository secrets.

## 3. Prepare source and a version tag

The prepared version is `versionName` **1.0.0**, `versionCode` **1**. The package ID stays `com.tunelyst.player`. Verify the private remote and its existing history before committing or pushing. Do not reinitialize the repository, overwrite remote history, or force-push. Merge or otherwise reconcile existing changes safely. The previous phone Git working-directory/ownership problem must be resolved before pushing.

For every subsequent release, increase `versionCode` and change `versionName` in `app/build.gradle.kts`; the pushed tag must match the version name, e.g. `v1.0.0`. Only tag a trusted, reviewed commit that is present in the intended branch. The workflow accepts version tags in `vMAJOR.MINOR.PATCH` format, uses the tag's source, and verifies the APK's application ID and version name before making a release.

Once the source is safely committed and pushed, and secrets are configured, push the chosen tag by your normal Git workflow. A matching tag triggers `.github/workflows/release.yml`: it validates the Gradle wrapper, builds one universal APK with R8 and resource shrinking, signs and verifies it, and creates a **draft** GitHub release containing the APK and SHA-256 checksum. With no bundled native libraries the APK uses architecture-independent Android bytecode; if native libraries are introduced later, the workflow requires each library for 32-bit ARM (`armeabi-v7a`), 64-bit ARM (`arm64-v8a`), 32-bit x86 and 64-bit x86. Android 10 or newer is still required. Compatibility with particular devices and audio formats still needs device testing. A failed build or missing signing secret does not create a release. The workflow does not publish the draft. The R8 mapping file is available privately as a workflow artifact for 90 days; download and keep it securely for diagnosing obfuscated crash reports.

Check the Actions run and draft release before proceeding. Download the APK and checksum into the same folder and run `sha256sum -c Tunelyst-v1.0.0.apk.sha256`. On a trusted system with Android build tools, run `apksigner verify --verbose --print-certs Tunelyst-v1.0.0.apk` and compare the signer SHA-256 fingerprint with your key's independently recorded fingerprint. A checksum by itself does not establish who signed the APK.

Install the **signed optimized release** on real test devices before publishing: test permission grant/revocation, first and subsequent library loading with large collections, Home and Recents, playback while foregrounded/backgrounded, notification/headset controls, search, select-all, shared audio and song details, and app reopening after a Recents swipe. The known rare phone-speaker stutter still needs separate investigation. Verify behavior on low-memory devices and relevant Android versions. Edit the draft notes and explicitly publish only after you are satisfied; a private repository's release remains private.

## Local signing (optional)

The build also accepts `TUNELYST_KEYSTORE_FILE`, `TUNELYST_STORE_PASSWORD`, `TUNELYST_KEY_ALIAS` and `TUNELYST_KEY_PASSWORD` as environment variables. Supply all four privately and run `bash ./gradlew :app:assembleRelease`. A pre-existing untracked `release.properties` with the four keys `storeFile`, `storePassword`, `keyAlias` and `keyPassword` is also supported; protect it and never commit it. With no complete signing configuration Gradle may produce an unsigned release; never distribute it as an official APK. Always verify the APK signature and certificate as described above.
