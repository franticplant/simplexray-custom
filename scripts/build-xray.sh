#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
#
# Builds Xray-core from source for every ABI the app ships, into
# app/src/main/jniLibs/<abi>/libxray.so. Versions come from version.properties:
# XRAY_CORE_VERSION is the tag built, NDK_VERSION the toolchain it is linked
# with. Go must already be on PATH (CI installs GO_VERSION with setup-go).
#
# The build is reproducible: -trimpath, no build ID, no VCS stamping, and a
# fixed checkout, so the same inputs give the same bytes.
#
# Usage: scripts/build-xray.sh [abi...]   (default: arm64-v8a x86_64)
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
prop() { grep "^$1=" "$root/version.properties" | cut -d= -f2- | tr -d '\r'; }

xray_version=$(prop XRAY_CORE_VERSION)
ndk_version=$(prop NDK_VERSION)
min_api=$(grep -oP 'minSdk\s*=?\s*\K[0-9]+' "$root/app/build.gradle" || true)
[[ -n $min_api ]] || { echo "could not read minSdk from app/build.gradle" >&2; exit 1; }
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
ndk=${ANDROID_NDK_HOME:-$sdk/ndk/$ndk_version}
toolchain=$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin
src=${XRAY_SRC:-$root/.xray-src}

if [[ -f $ndk/source.properties ]]; then
    have_ndk=$(grep -m1 '^Pkg.Revision' "$ndk/source.properties" | cut -d= -f2 | tr -d ' \r')
    if [[ $have_ndk != "$ndk_version" ]]; then
        echo "NDK at $ndk is $have_ndk, but NDK_VERSION is $ndk_version" >&2
        exit 1
    fi
fi

if [[ ! -x "$toolchain/clang" ]]; then
    echo "NDK $ndk_version not found at $ndk; install it with: sdkmanager 'ndk;$ndk_version'" >&2
    exit 1
fi

if [[ ! -d "$src/.git" ]]; then
    git clone --quiet --filter=blob:none https://github.com/XTLS/Xray-core.git "$src"
fi
git -C "$src" fetch --quiet --tags origin
git -C "$src" -c advice.detachedHead=false checkout --quiet --force "$xray_version"
commit=$(git -C "$src" rev-parse --short=7 HEAD)

abis=("$@")
[[ ${#abis[@]} -gt 0 ]] || abis=(arm64-v8a x86_64)

for abi in "${abis[@]}"; do
    case $abi in
        arm64-v8a) goarch=arm64 triple=aarch64-linux-android ;;
        x86_64) goarch=amd64 triple=x86_64-linux-android ;;
        *) echo "unsupported ABI: $abi" >&2; exit 1 ;;
    esac
    echo "Building Xray-core $xray_version ($commit) for $abi with $(go version | cut -d' ' -f3)"
    out=$root/app/src/main/jniLibs/$abi
    mkdir -p "$out"
    (
        cd "$src"
        GOOS=android GOARCH=$goarch CGO_ENABLED=1 CC=$toolchain/$triple$min_api-clang \
            go build -o "$out/libxray.so" -trimpath -buildvcs=false \
            -ldflags="-X github.com/xtls/xray-core/core.build=$commit -s -w -buildid=" ./main
    )
done
