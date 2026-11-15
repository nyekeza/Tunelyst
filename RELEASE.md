# Preparing a Tunelyst release

Tunelyst is public. A new version tag starts a build and publishes the release automatically if the checks pass. Test your changes and check the version before pushing the tag.

## Protect the signing key

Keep the existing release keystore, its alias, and both passwords safe. Use the **same key for every update**. Losing it or switching keys can prevent people from updating their installed app. Keep an encrypted backup away from the project and never put the key or passwords in a commit, issue, or log.

Android won't update an installation signed with a different key. Uninstalling the old app can remove its playlists and settings.

## Keep the release secrets safe

The repository needs these GitHub Actions secrets:

- `TUNELYST_KEYSTORE_BASE64`
- `TUNELYST_STORE_PASSWORD`
- `TUNELYST_KEY_ALIAS`
- `TUNELYST_KEY_PASSWORD`

Set them under **Settings → Secrets and variables → Actions**. Do not print or share their values. Review changes to workflows before running them with access to secrets. The repository is public, so treat Actions logs and artifacts as potentially visible to others; never put private information in them.

## Prepare the update

1. Start from the current GitHub `main` and review the changes. Do not push an old local branch over it or force-push.
2. Increase `versionCode` and update `versionName` in `app/build.gradle.kts`.
3. Test the changes, then commit and push the reviewed source.
4. Create and push a matching tag, such as `v1.0.1`. The workflow accepts tags in the form `vMAJOR.MINOR.PATCH`.
5. Check the Actions run. It builds and signs one APK, checks the version and signature, and publishes it with a checksum. If native libraries are included, it checks support for 32-bit and 64-bit ARM and x86.

## Check the release

After publishing, download the APK and checksum from the release. Check the checksum and compare the signing certificate with the one recorded for the release key. Testing the signed APK on real devices is recommended; it can behave differently from a debug build.

Check the release notes and edit them if needed. The APK needs Android 10 or later. The mapping file is retained as a workflow artifact for 90 days; consider its visibility before storing it elsewhere.

For optional local signing, the build supports the `TUNELYST_KEYSTORE_FILE`, `TUNELYST_STORE_PASSWORD`, `TUNELYST_KEY_ALIAS`, and `TUNELYST_KEY_PASSWORD` environment variables, or a private untracked `release.properties`. Supply all four values. Never distribute an unsigned APK as an official release; verify the signature first.
