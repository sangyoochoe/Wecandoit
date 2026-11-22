# Craft Anything for Android

Android native port of [Craft Anything](https://github.com/storytold/craft-anything).

## Build

GitHub Actions builds the Android debug APK on every push and pull request, and builds the release APK on pushes to `main` or manual workflow runs.

The workflow checks out the upstream parser source at a fixed commit so the same source revision is used for repeatable builds. It runs the upstream `craft-anything-formats` library tests before compiling the Android JNI libraries.

## Android engine port status

The verified upstream workflow contract, Android blockers, and implementation/test gates are documented in [docs/ANDROID_ENGINE_PORT_STATUS.md](docs/ANDROID_ENGINE_PORT_STATUS.md). The full upstream Study → Build → Check → Refine engine is not yet connected to Android; do not treat the current APK as a complete app-generation port.

## Current Android scope

- Kotlin + Jetpack Compose Android application
- Rust JNI bridge for Android `arm64-v8a` and `x86_64`
- Upstream extended format parser crate enabled (`CRAFT_ANDROID_WITH_UPSTREAM_FORMATS=1`)
- APK/DEX/manifest/resource analysis UI and tools
- AI provider configuration and Android Keystore-backed credential storage

## Known limitations

- The full desktop AI agent engine is **not** linked in this Android build (`CRAFT_ANDROID_WITH_UPSTREAM_ENGINE=0`). Desktop process/PTY backends still need Android-specific implementations.
- A successful CI build and parser unit tests do not prove that every file type works on a physical device. Device-level test coverage is still required.
- The CI release APK is currently unsigned. Sign it with a separately managed Android release keystore before distributing it. Never commit keystore files or passwords to this repository.

## Install and first use

Download the debug APK from the latest successful **Android APK** workflow run's Artifacts section. Install it on a compatible Android device, choose a file with **파일 변경**, then tap **기기에서 분석하기**. The app reads the selected file for analysis; it does not execute the selected file.

For AI-assisted questions, configure a provider and API key in Settings. AI requests may transmit the selected analysis context to the provider you choose; do not submit confidential files unless you are comfortable with that provider's data handling.
