#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
#
# Verifies the signature of every APK in a directory and prints its signing
# certificate. Always fails on the public debug.keystore certificate, so a
# misconfigured release can never ship debug-signed; with EXPECTED set (hex
# SHA-256 of the release certificate, any case), also fails unless every APK
# is signed with it.
#
# Usage: EXPECTED=<sha256> scripts/verify-signatures.sh <dir>
set -euo pipefail

dir=${1:?usage: $0 <dir>}
root=$(cd "$(dirname "$0")/.." && pwd)
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
apksigner="$(ls -d "$sdk"/build-tools/* | sort -V | tail -1)/apksigner"
expected=${EXPECTED:-}
debug_cert=$(keytool -list -v -keystore "$root/debug.keystore" -storepass android -alias androiddebugkey |
    grep -m1 'SHA256:' | awk '{print $2}' | tr -d : | tr 'A-F' 'a-f')

for apk in "$dir"/*.apk; do
    got=$("$apksigner" verify --print-certs "$apk" | grep -m1 'certificate SHA-256 digest' | awk '{print $NF}')
    got=${got,,}
    echo "$apk: $got"
    if [[ $got == "$debug_cert" ]]; then
        echo "::error::$apk is signed with the public debug key"
        exit 1
    fi
    if [[ -n $expected && $got != "${expected,,}" ]]; then
        echo "::error::$apk is signed by $got, expected $expected"
        exit 1
    fi
done
