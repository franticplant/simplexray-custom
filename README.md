# SimpleXray

<img src="https://raw.githubusercontent.com/lhear/SimpleXray/main/metadata/en-US/images/icon.png" alt="icon" width="150">

[![GitHub Release](https://img.shields.io/github/v/release/lhear/SimpleXray)](https://github.com/lhear/SimpleXray/releases)
[![FDroid Release](https://img.shields.io/f-droid/v/com.simplexray.an.svg)](https://f-droid.org/packages/com.simplexray.an)
[![GitHub License](https://img.shields.io/github/license/lhear/SimpleXray)](LICENSE)
[![GitHub Actions Workflow Status](https://img.shields.io/github/actions/workflow/status/lhear/SimpleXray/.github%2Fworkflows%2Fbuild.yml)](https://github.com/lhear/SimpleXray/actions)
![GitHub Downloads (all assets, all releases)](https://img.shields.io/github/downloads/lhear/SimpleXray/total)

SimpleXray is a high-performance proxy client for Android, **built upon the robust Xray-core ([@XTLS/Xray-core](https://github.com/XTLS/Xray-core))**.

It features an **innovative approach**: **directly executing the official Xray-core binary**, unlike traditional JNI methods. This method isolates core logic from the app layer, boosting stability and maximizing Xray-core's native performance.

---

## Key Features

*   **Multi-Instance Support**: Run and configure multiple concurrent Xray core instances simultaneously. Easily toggle profiles, view per-instance traffic, and inspect individual API ports.
*   **Enhanced Stability**: Executes Xray-core as independent child processes, avoiding JNI complexities, memory leaks, and core crashes.
*   **High Performance**: Leverages Xray-core's native speed and integrates [@heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) for low-latency Tun2socks performance.
*   **Flexible UX**: Material 3 interface with per-profile status pills, instance controls, and live traffic graphs.

---

## Architecture & Multi-Instance Documentation

For complete technical documentation on the Multi-Instance core architecture, port management, and process lifecycle, see:
👉 **[Multi-Instance Guide](docs/MULTI_INSTANCE_GUIDE.md)**

---

## Build & Keystore Configuration

SimpleXray supports dynamic APK signing using customizable environment variables or property files.

### 1. Build Requirements
- JDK 17+
- Android SDK (API 35)

### 2. Signing with Custom Keystore
Set environment variables before invoking Gradle:
```bash
export KEYSTORE_PATH="/path/to/keystore.jks"
export KEYSTORE_PASSWORD="your_store_password"
export KEY_ALIAS="your_key_alias"
export KEY_PASSWORD="your_key_password"

./gradlew assembleRelease
```
Alternatively, configure `store.properties` in the project root directory:
```properties
storeFile=/path/to/keystore.jks
storePassword=your_store_password
keyAlias=your_key_alias
keyPassword=your_key_password
```

---

## License

This project is licensed under the **[Mozilla Public License Version 2.0](LICENSE)**.
