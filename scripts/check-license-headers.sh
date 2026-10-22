#!/bin/sh
# SPDX-License-Identifier: MPL-2.0
#
# check-license-headers.sh checks that every Kotlin and Java source file under
# app/src starts with the project's license header.

want='// Copyright (c) SimpleXray contributors
// SPDX-License-Identifier: MPL-2.0'

root=$(cd "$(dirname "$0")/.." && pwd)
fail=0
for file in $(find "$root/app/src" \( -name '*.kt' -o -name '*.java' \) -not -path '*/jni/*'); do
	if [ "$(head -2 "$file")" != "$want" ]; then
		fail=1
		echo "${file#"$root"/} doesn't start with the license header:"
		echo "$want" | sed -e 's/^/    /'
	fi
done
exit $fail
