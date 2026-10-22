# Third-party notices

SimpleXray is licensed under the [Mozilla Public License 2.0](LICENSE). Its
APKs bundle the third-party software below, each under its own license.

## SimpleXray

This repository is a fork of [lhear/SimpleXray](https://github.com/lhear/SimpleXray).
Copyright (c) SimpleXray contributors. MPL-2.0.

## Xray-core

`libxray.so` in every APK is [XTLS/Xray-core](https://github.com/XTLS/Xray-core),
built unmodified from source at the tag named by `XRAY_CORE_VERSION` in
`version.properties`. It is licensed under the Mozilla Public License 2.0; its
source is available at that tag. Xray-core in turn links Go modules under
permissive licenses (BSD-3-Clause, MIT, Apache-2.0 and others), listed in its
`go.mod`.

`app/src/main/proto/command.proto` is taken from Xray-core
(`app/stats/command/command.proto`), MPL-2.0.

## hev-socks5-tunnel

`libhev-socks5-tunnel.so` is built from
[heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel)
(MIT License), included as a git submodule, together with its bundled
dependencies:

- [hev-task-system](https://github.com/heiher/hev-task-system) — MIT License
- [lwIP](https://savannah.nongnu.org/projects/lwip/) — BSD-3-Clause
- [libyaml](https://github.com/yaml/libyaml) — MIT License

## Geo data

`geoip.dat` and `geosite.dat` come from
[lhear/v2ray-rules-dat](https://github.com/lhear/v2ray-rules-dat), which is
generated from upstream sources including
[Loyalsoldier/v2ray-rules-dat](https://github.com/Loyalsoldier/v2ray-rules-dat)
(GPL-3.0) and [v2fly/domain-list-community](https://github.com/v2fly/domain-list-community)
(MIT). They are data files read at runtime and are not linked into the
program.

## Android libraries

The app depends on the libraries declared in `gradle/libs.versions.toml`,
including AndroidX and Jetpack Compose, Material Components, gRPC Java,
Protocol Buffers (javalite), Gson, OkHttp (all Apache-2.0),
[LazyColumnScrollbar](https://github.com/nanihadesuka/LazyColumnScrollbar) (MIT)
and [Reorderable](https://github.com/Calvin-LL/Reorderable) (Apache-2.0).

Each release publishes an SPDX SBOM (`simplexray-<tag>.spdx.json`) listing the
exact Gradle dependency versions it was built with.
