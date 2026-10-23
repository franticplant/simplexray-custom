# Building

## Toolchain

| What | Version | Pinned in |
| --- | --- | --- |
| JDK | 21 | `.github/actions/setup-build/action.yml` |
| Gradle | wrapper | `gradle/wrapper/gradle-wrapper.properties` |
| AGP, Kotlin, libraries | | `gradle/libs.versions.toml` |
| compileSdk / targetSdk / minSdk | 37 / 36 / 29 | `app/build.gradle` |
| NDK | `NDK_VERSION` | `version.properties` |
| Go (for Xray-core) | `GO_VERSION` | `version.properties` |
| Xray-core | `XRAY_CORE_VERSION` | `version.properties` |
| Geo data | `GEOIP_VERSION`, `GEOSITE_VERSION` | `version.properties` |
| hev-socks5-tunnel | submodule commit | `app/src/main/jni/hev-socks5-tunnel` |

Dependabot keeps the version catalog and the pinned GitHub Actions current.
`.github/workflows/update-xray.yml` updates Xray-core, Go and the geo data. The
NDK and hev-socks5-tunnel are updated by hand. When updating
hev-socks5-tunnel, check `src/hev-jni.c`: its `JNI_OnLoad` registers natives on
`TProxyService` and fails on any signature mismatch.

## Native components

The APK contains two native pieces. Neither is checked in.

- **hev-socks5-tunnel** is built by Gradle through `ndk-build` from the
  submodule. Clone with `--recurse-submodules`, or run
  `git submodule update --init --recursive`.
- **Xray-core** is built from source by `scripts/build-xray.sh` into
  `app/src/main/jniLibs/<abi>/libxray.so`. The script clones Xray-core into
  `.xray-src/`, checks out `XRAY_CORE_VERSION`, and cross-compiles with the
  NDK's clang. Go must be on `PATH`.

`scripts/fetch-geodata.sh` downloads `geoip.dat` and `geosite.dat` into
`app/src/main/assets/`, checking each against its published SHA-256.

## Signing

All build types are signed with the same key, so a debug build installs over
a release build. The key is the first one found among:

1. `SIMPLEXRAY_KEYSTORE` and `SIMPLEXRAY_KEYSTORE_PASSWORD` (and optionally
   `SIMPLEXRAY_KEY_ALIAS`, `SIMPLEXRAY_KEY_PASSWORD`) in the environment;
2. a properties file with `storeFile`, `storePassword`, `keyAlias` and
   `keyPassword`. The build looks at `$SIMPLEXRAY_SIGNING_PROPERTIES`, then
   `store.properties` in the repository root (git-ignored), then
   `~/.config/simplexray/signing.properties`. A relative `storeFile` is
   resolved against the properties file's directory;
3. the checked-in `debug.keystore`. Its key is public, so it is only good for
   testing. `scripts/package-release.sh` and `scripts/verify-signatures.sh`
   refuse it.

`-Punsigned` builds unsigned APKs. The reproducibility check uses this.

## Checks

CI (`.github/workflows/android.yml`) runs all of these, and so can you:

```sh
scripts/check-license-headers.sh   # MPL-2.0 SPDX header on every source file
./gradlew ktfmtCheck               # formatting (ktfmt, kotlinlang style); fix with ktfmtFormat
./gradlew testDebugUnitTest        # JVM unit tests
./gradlew lintDebug                # Android lint against app/lint-baseline.xml
```

Lint treats every warning as an error, and there is no baseline. Fix
findings. If one is truly a false positive, suppress it where it occurs, with
a comment saying why.

CodeQL (`.github/workflows/codeql.yml`) scans the Kotlin and Java code and
the workflows on every pull request, every push to `main`, and weekly. Its
findings appear under the repository's Security tab.

`scripts/secret-scan.sh` is a pre-commit hook that rejects staged keys and
tokens. Install it with:

```sh
ln -s ../../scripts/secret-scan.sh .git/hooks/pre-commit
```

## Reproducibility

Release APKs are reproducible from the tag. Xray-core is built with
`-trimpath`, no build ID and no VCS stamping. The ndk-build output maps source
paths, has no build ID, and its source list is sorted bytewise, independent
of the locale. The APK carries no dependency-metadata block.
`scripts/verify-reproducible.sh <tag>` checks a published release. The release
workflow runs the same check on a second runner.
