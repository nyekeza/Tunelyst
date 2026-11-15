# Tunelyst

Tunelyst is an Android music player for audio stored on the device. The application ID is `com.tunelyst.player`; the minimum supported Android version is Android 10 (API 29).

## Build

Open the project in Code On The Go or another compatible Android Gradle environment. The project uses JDK 21, Gradle 9.6.1, Android Gradle Plugin 9.3.1 and Android SDK 36. Dependency resolution may require an internet connection.

A debug build can be created with `bash ./gradlew :app:assembleDebug`. The release build enables R8 code optimization and resource shrinking and produces a single universal APK. The release workflow checks for 32-bit and 64-bit ARM and x86 support if native libraries are added. Signing and draft GitHub release setup are documented in [RELEASE.md](RELEASE.md).

## Important

- Music files are not included in this repository. Permissions are needed to read the user's local library.
- Release keys, passwords, local SDK configuration and verification/build files must not be committed. Ignore rules do not untrack files that are already committed.
- A release signed with a new key cannot update an existing install signed with a different key. Removing an app may remove its local app data; check and preserve data first.
- A successful build is not a substitute for testing the signed release APK on actual devices.
