# SimpleXray (custom)

<img src="metadata/en-US/images/icon.png" alt="SimpleXray icon" width="120">

[![Android CI](https://github.com/franticplant/simplexray-custom/actions/workflows/android.yml/badge.svg)](https://github.com/franticplant/simplexray-custom/actions/workflows/android.yml)
[![Release](https://img.shields.io/github/v/release/franticplant/simplexray-custom)](https://github.com/franticplant/simplexray-custom/releases)
[![License: MPL-2.0](https://img.shields.io/github/license/franticplant/simplexray-custom)](LICENSE)

An Android proxy client for [Xray-core](https://github.com/XTLS/Xray-core) that
runs the Xray-core executable itself, as child processes, rather than linking it
in over JNI. It can run several Xray instances at once.

This is an independently maintained fork of
[lhear/SimpleXray](https://github.com/lhear/SimpleXray), which is no longer
developed. It installs as `io.github.franticplant.simplexray`, so it sits
alongside the original app rather than replacing it. Configs move across
with the app's export and import.

## What it does

- **Xray-core as a separate process.** Each profile runs the Xray-core binary
  in its own process. A core crash cannot take the app down, nothing leaks
  across JNI, and the core runs at native speed.
- **Several instances at once.** One profile carries the VPN tunnel. Others run
  core-only, as local SOCKS/HTTP proxies for particular apps or chains. Each
  instance gets its own loopback API port and statistics, its own log tag, and
  its own start, stop and restart controls. See the
  [multi-instance guide](docs/MULTI_INSTANCE_GUIDE.md).
- **Tun2socks in C.** [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel)
  carries the VPN interface's traffic into Xray's SOCKS inbound with low
  latency.
- **Activity and history, on the device.** Sessions, time to connect, core
  crashes and their reasons, network changes, and data per day and per config,
  split into proxied, direct and blocked traffic. Logs are kept per session.
  History is kept for a set number of days and can be exported as a zip, with
  addresses redacted. See [observability](docs/observability.md).
- **Material 3 UI** with per-profile status, live traffic graphs, app-based
  routing, and config import from VLESS, VMess, Trojan and Shadowsocks share
  links and SimpleXray's own format.

## Install

Download the APK for your device from the
[releases page](https://github.com/franticplant/simplexray-custom/releases):
`arm64-v8a` for almost every phone, `x86_64` for emulators and Chromebooks, or
`universal`.

Every release file comes with build-provenance and SBOM attestations:

```sh
gh attestation verify simplexray-v1.11.0-arm64-v8a.apk -R franticplant/simplexray-custom
```

The build is reproducible. `scripts/verify-reproducible.sh <tag>` rebuilds a
release from source and checks that it matches the published APKs byte for
byte, apart from the signature.

## Build

You need JDK 21, the Android SDK, the NDK version named in
`version.properties`, and Go (also pinned in `version.properties`).

```sh
git clone --recurse-submodules https://github.com/franticplant/simplexray-custom
cd simplexray-custom
scripts/fetch-geodata.sh     # geoip.dat / geosite.dat, checksum-verified
scripts/build-xray.sh        # Xray-core from source, per ABI
./gradlew assembleDebug
```

[docs/building.md](docs/building.md) covers signing, checks, and how versions
are managed. [docs/releasing.md](docs/releasing.md) covers how releases are
made.

## Documentation

See [docs/README.md](docs/README.md).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Report security problems privately, as
described in [SECURITY.md](SECURITY.md).

## License

[Mozilla Public License 2.0](LICENSE). Bundled third-party components and their
licenses are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Origin

SimpleXray was written by [lhear](https://github.com/lhear) and contributors.
This fork adds multi-instance cores, a rebuilt build and release pipeline, and
ongoing dependency maintenance.
