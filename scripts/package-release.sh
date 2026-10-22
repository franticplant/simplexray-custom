#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
#
# Builds the signed release APKs (per ABI plus universal) and collects them in
# dist/ as simplexray-<tag>-<abi>.apk, with a SHA256SUMS file.
#
# Expects geo data and Xray-core in place (scripts/fetch-geodata.sh,
# scripts/build-xray.sh) and a release key configured (see app/build.gradle);
# refuses to package APKs signed with the public debug key unless
# ALLOW_DEBUG_KEY=1.
#
# Usage: scripts/package-release.sh <tag>
set -euo pipefail

tag=${1:?usage: $0 <tag>}
root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"

for f in app/src/main/assets/geoip.dat app/src/main/assets/geosite.dat \
    app/src/main/jniLibs/arm64-v8a/libxray.so app/src/main/jniLibs/x86_64/libxray.so; do
    [[ -f $f ]] || { echo "$f is missing; run scripts/fetch-geodata.sh and scripts/build-xray.sh" >&2; exit 1; }
done

rm -rf app/build/outputs/apk/release
./gradlew --no-configuration-cache -q assembleRelease

export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
apksigner="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)/apksigner"
debug_cert=$(keytool -list -v -keystore debug.keystore -storepass android -alias androiddebugkey |
    grep -m1 'SHA256:' | awk '{print $2}' | tr -d : | tr 'A-F' 'a-f')

rm -rf dist && mkdir -p dist
for apk in app/build/outputs/apk/release/app-*-release.apk; do
    abi=$(basename "$apk" | sed -E 's/^app-(.*)-release\.apk$/\1/')
    cert=$("$apksigner" verify --print-certs "$apk" | grep -m1 'certificate SHA-256 digest' | awk '{print $NF}')
    if [[ $cert == "$debug_cert" && ${ALLOW_DEBUG_KEY:-0} != 1 ]]; then
        echo "$apk is signed with the public debug key; configure a release key (see app/build.gradle)" >&2
        exit 1
    fi
    install -m 0644 "$apk" "dist/simplexray-$tag-$abi.apk"
done
(cd dist && sha256sum *.apk > SHA256SUMS)
ls -l dist
