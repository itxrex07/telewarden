# TeleWarden Android

TeleWarden is an Android app for backing up and browsing files through Telegram.
This repository contains the Android application and the shared Kotlin/Compose
code required to build it; desktop and web targets are not included.

## Requirements

- Android Studio with JDK 17
- Android SDK platform 37 and build tools installed
- Android 8.0 (API 26) or newer for the app

## Build

```bash
./gradlew :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`.

For a release build:

```bash
./gradlew :app:assembleRelease
```

A release keystore can be supplied with `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`,
`KEY_ALIAS`, and `KEY_PASSWORD` environment variables.

## Project layout

- `app/` — Android application module and build configuration
- `android/` — Android manifest, resources, and platform implementation
- `shared/` — shared storage, crypto, sync, and transfer code
- `ui/` — shared Compose UI
