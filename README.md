# Sambhav TV OS — TCL / Android 9

A lightweight, Apple TV-inspired Android TV launcher written in native Kotlin Views.
Designed for Android 9 (API 28+) and TV remotes, including TCL Android TV models.

## Included
- Cinematic dark home screen with a large featured panel
- Automatically discovers launchable TV and Android apps
- Remote-friendly focus, D-pad and OK/Enter app launching
- Clock and date
- No third-party libraries, account setup, or network permission
- GitHub Actions workflow to build and upload a debug APK

## Build on GitHub using a phone
1. Create a GitHub repository named `Sambhav-TV-OS`.
2. Extract this ZIP and upload **all contents** to the repository root. Include the hidden `.github` folder.
3. Open **Actions** and enable workflows if GitHub asks.
4. Select **Build Sambhav TV OS APK** and press **Run workflow** (or push to `main`).
5. Open the completed workflow run and download `Sambhav-TV-OS-debug-apk` from Artifacts.

## Install on a TCL TV
1. Download the APK artifact and copy it to a USB drive, or transfer it using a trusted local file-transfer method.
2. On the TCL TV, open the file manager and install the APK. You may need to allow installation from that file manager under Android security settings.
3. Open **Sambhav TV OS** from the Apps list.
4. Press the Home button. If TCL firmware displays a launcher chooser, select Sambhav TV OS and choose **Always** if you want it as the default.

## TCL compatibility notes
TCL sells multiple TV firmware variants. Android 9 alone does not guarantee that every model allows a third-party launcher to replace the stock Home app. Some TCL firmware builds keep the stock launcher assigned to the Home key or hide the launcher chooser. In that case Sambhav TV OS can still be opened from Apps, but replacing the Home key may not be possible without model-specific firmware support. This app does not root the TV or bypass manufacturer restrictions.

## Project details
- Application ID: `in.sambhav.tvos`
- Minimum Android: 9 / API 28
- Target Android: 9 / API 28
- Build: Android Gradle Plugin 8.7.3, Gradle 8.9, JDK 17
- Main activity: `app/src/main/java/in/sambhav/tvos/MainActivity.kt`

This is an original launcher design inspired by the clean, cinematic feel of premium TV interfaces; it is not an Apple product and does not include streaming content.
