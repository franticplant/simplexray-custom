#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
#
# Downloads geoip.dat and geosite.dat at the versions pinned in
# version.properties into app/src/main/assets, checking each against the
# .sha256sum file published next to it.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
prop() { grep "^$1=" "$root/version.properties" | cut -d= -f2- | tr -d '\r'; }
base=https://github.com/lhear/v2ray-rules-dat/releases/download
assets=$root/app/src/main/assets
mkdir -p "$assets"

fetch() {
    local name=$1 version=$2 tmp
    tmp=$(mktemp -d)
    trap 'rm -rf "$tmp"' RETURN
    curl -fsSL --retry 3 -o "$tmp/$name" "$base/$version/$name"
    curl -fsSL --retry 3 -o "$tmp/$name.sha256sum" "$base/$version/$name.sha256sum"
    (cd "$tmp" && sha256sum --quiet -c "$name.sha256sum")
    mv "$tmp/$name" "$assets/$name"
    echo "$name $version: OK"
}

fetch geoip.dat "$(prop GEOIP_VERSION)"
fetch geosite.dat "$(prop GEOSITE_VERSION)"
