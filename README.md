# Craft Anything for Android

Android native port of [Craft Anything](https://github.com/storytold/craft-anything).

## Build

The repository is being initialized for the Android port. Add the Android project files (including `gradlew`, `gradle/wrapper/`, `settings.gradle.kts`, and the `app/` module) to the repository root.

Once the project is present, GitHub Actions builds a debug APK on every push and manual workflow run. The APK is uploaded as a workflow artifact.

## Current scope

- Kotlin + Jetpack Compose Android application
- Rust JNI bridge (where enabled by the project build configuration)
- APK/DEX/manifest/resource analysis
- AI provider configuration and Android Keystore-backed credential storage

The porting status and platform limitations must be documented accurately; a successful Android build does not by itself imply full feature parity with the desktop Rust AI/agent stack.
