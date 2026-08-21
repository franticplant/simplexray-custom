# SimpleXray Multi-Instance Architecture & Configuration Guide

SimpleXray supports running **multiple concurrent Xray core instances**, allowing users to maintain active proxy connections across different profiles simultaneously.

---

## 1. Multi-Instance Core Architecture

The multi-instance subsystem consists of three main components:

1. **`XrayInstanceManager`**: Thread-safe manager operating at the application core.
   - Manages concurrent process lifecycles for each Xray core profile.
   - Allocates non-conflicting loopback IP addresses (`127.x.y.z`) and unique API ports for gRPC statistics querying.
   - Collects per-instance system metrics (goroutines, memory allocation, uptime, traffic).
   - Channels instance logs tagged with profile names (e.g., `[ProfileName] Log entry`).
2. **`TProxyService`**: Android Service providing TUN / VpnService capabilities.
   - **Primary Instance**: The active configuration bound to the system VPN tunnel.
   - **Secondary Instances**: Core-only standalone proxy instances listening on local inbound ports (e.g. SOCKS5/HTTP) for specific applications or proxy chains.
3. **Multi-Instance UX Layer**:
   - **Config Screen**: Visual badges for Primary VPN / Active Core status, per-profile toggle switches, live throughput display, and API port indicators.
   - **Dashboard**: Aggregated global system statistics and per-instance detail cards with quick controls (Start / Stop / Restart).

---

## 2. Configuration & Inbound Port Guidelines

When running multiple Xray instances concurrently:
- Each instance runs its own Xray JSON profile.
- **Port Isolation**: Ensure each active profile uses distinct local inbound listening ports (e.g., Instance 1 on SOCKS port `10808`, Instance 2 on SOCKS port `10809`).
- **API Port Allocation**: SimpleXray automatically assigns internal API ports for metrics monitoring to avoid collisions.

---

## 3. Building and Signing with Custom Keystores

SimpleXray supports dynamic APK signing via environment variables, Gradle properties, or local properties files.

### Option A: Using Environment Variables
```bash
export KEYSTORE_PATH="/path/to/your/release.keystore"
export KEYSTORE_PASSWORD="YourStorePassword"
export KEY_ALIAS="YourKeyAlias"
export KEY_PASSWORD="YourKeyPassword"

./gradlew assembleRelease
```

### Option B: Using `store.properties`
Create a `store.properties` file in the project root directory:
```properties
storeFile=/path/to/your/release.keystore
storePassword=YourStorePassword
keyAlias=YourKeyAlias
keyPassword=YourKeyPassword
```

### Option C: Using Gradle Properties
Pass properties directly when invoking Gradle:
```bash
./gradlew assembleRelease \
  -PkeystorePath="/path/to/your/release.keystore" \
  -PstorePassword="YourStorePassword" \
  -PkeyAlias="YourKeyAlias" \
  -PkeyPassword="YourKeyPassword"
```

---

## 4. Environment & Cache Directories

To use isolated build and package caches (e.g., on custom build systems or mounted volumes):

```bash
export GRADLE_USER_HOME="${CUSTOM_CACHE_DIR}/.gradle"
export ANDROID_HOME="${CUSTOM_CACHE_DIR}/Android/Sdk"
export XDG_CACHE_HOME="${CUSTOM_CACHE_DIR}/.cache"
export TMPDIR="${CUSTOM_CACHE_DIR}/tmp"

./gradlew assembleDebug
```

---

## 5. System Requirements

- **Android OS**: Android 10 (API level 29) or higher.
- **Architecture**: `arm64-v8a`, `x86_64`.
