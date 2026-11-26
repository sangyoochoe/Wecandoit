# REA Android integration

Wecandoit delegates static APK inspection to a separately installed REA CLI through a small LAN companion. The Android app does not embed JADX, JDK, Node.js or REA's native host dependencies.

## Host setup

1. Install REA and its Android APK prerequisites (full JDK and the matching headless JADX MCP JAR).
2. Put rea on PATH and verify: rea inspect-android-package /absolute/path/app.apk --json
3. Set a long random token and start the bridge:
   export WECANDOIT_REA_TOKEN='replace-with-a-long-random-secret'
   export WECANDOIT_REA_PORT=8765
   node rea-android-bridge/server.mjs
4. Allow TCP 8765 only on a trusted LAN. Use HTTPS or a VPN outside a trusted local network. The bridge requires a bearer token, limits APK uploads to 80 MiB, writes temporary files with owner-only permissions, invokes REA without a shell, and deletes the temporary APK after analysis.
5. In Wecandoit, enter the host LAN address and same token, choose an APK, and run static analysis.

## Scope and safety

This bridge exposes inspect-android-package only. It delegates to REA's real CLI and returns its JSON Evidence. It does not install or launch the APK, connect to a phone with ADB, run Frida/JEB, or claim runtime behavior. Those REA capabilities require a separately configured host-side ADB/device and additional explicit, mutation-gated operations. Analysis runs on the host; the APK is transferred from Android to that host.

REA prerequisites: https://github.com/morluto/rea/blob/main/docs/guides/android/android-analysis.md
