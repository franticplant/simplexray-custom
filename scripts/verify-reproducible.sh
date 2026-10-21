#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
#
# Rebuilds a SimpleXray release from source and checks that every APK is
# byte-for-byte the published one, apart from the signature: the release's
# signature is copied onto the rebuilt, unsigned APK, which must then verify.
#
# Usage: scripts/verify-reproducible.sh <tag> [dir]
#   dir        the release's APKs; downloaded from the GitHub release if omitted
# Environment:
#   WORK_DIR   where to clone and build (default: a new temporary directory)
#   REPO_URL   repository to clone (default: GitHub)
#
# Needs git, gh (unless dir is given), Go at the release's GO_VERSION, JDK 21,
# the Android SDK with the release's NDK_VERSION, and apksigcopier
# (pip install apksigcopier).
set -euo pipefail

tag=${1:?usage: $0 <tag> [dir-with-release-apks]}
repo=franticplant/simplexray-custom
repo_url=${REPO_URL:-https://github.com/$repo}
work=${WORK_DIR:-$(mktemp -d)}
official=$(realpath -m "${2:-$work/official}")
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}

command -v apksigcopier > /dev/null || { echo "apksigcopier not found: pip install apksigcopier" >&2; exit 1; }

if [[ -z ${2:-} ]]; then
    mkdir -p "$official"
    gh release download "$tag" -R "$repo" -p '*.apk' -D "$official"
fi
apks=("$official"/simplexray-"$tag"-*.apk)
[[ -f ${apks[0]} ]] || { echo "no simplexray-$tag-*.apk in $official" >&2; exit 1; }

# apksigcopier runs apksigner, which the SDK does not put on PATH.
export PATH="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1):$PATH"

echo "Rebuilding $tag in $work"
rm -rf "$work/simplexray"
git clone -q --recurse-submodules --branch "$tag" "$repo_url" "$work/simplexray"
cd "$work/simplexray"

want_go=$(grep '^GO_VERSION=' version.properties | cut -d= -f2)
have_go=$(go env GOVERSION)
if [[ $have_go != "go$want_go" ]]; then
    echo "warning: $tag was built with go$want_go, this is $have_go; libxray.so will likely differ" >&2
fi

# A fresh Go build cache, so nothing is reused from an earlier build.
export GOCACHE="$work/gocache" GOTOOLCHAIN=local
scripts/fetch-geodata.sh
scripts/build-xray.sh
./gradlew --no-build-cache --no-configuration-cache -q assembleRelease -Punsigned

failed=0
for apk in "${apks[@]}"; do
    abi=${apk##*/simplexray-"$tag"-}
    abi=${abi%.apk}
    rebuilt=app/build/outputs/apk/release/app-$abi-release-unsigned.apk
    if apksigcopier compare "$apk" --unsigned "$rebuilt"; then
        echo "reproduced: ${apk##*/}"
    else
        echo "NOT REPRODUCED: ${apk##*/} (see apksigcopier's output above; rebuilt: $work/simplexray/$rebuilt)"
        failed=1
    fi
done
exit $failed
