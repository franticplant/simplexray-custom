---
name: simplexray-release
description: Cut, verify or troubleshoot a simplexray-custom release. Use whenever the task mentions releasing simplexray, version.properties, release notes, v* tags, the release workflow, "Check the build reproduces", reproduce-<tag> artifacts, or missing release APKs — even if the user just says "make a release" or "why is there no APK".
---

# Releasing simplexray-custom

The project publishes signed Android APKs from `v*` tags. `.github/workflows/release.yml`
builds per-ABI and universal APKs, checks the signing certificate, writes `SHA256SUMS` and an
SPDX SBOM, attests them, rebuilds from source in a second job to check reproducibility, and
publishes the GitHub Release. The APKs are release assets — never commit them to the git tree.

## Release checklist

1. Start from an up-to-date, green `main`:

   ```sh
   git fetch --tags origin
   git switch main
   git pull --ff-only
   ```

   Confirm the latest `Android CI` and `CodeQL` runs on `main` passed.

2. Bump both fields in `version.properties`:
   - `APP_VERSION_CODE` — increment by one.
   - `APP_VERSION_NAME` — the user-facing semantic version.

3. Write `docs/release-notes/v<version>.md`. The workflow looks for the exact tag name; without
   it, GitHub-generated notes are used. Describe user-visible changes, not commit titles.

4. Run the same checks CI runs. `ANDROID_HOME` must point at an installed Android SDK:

   ```sh
   ./gradlew ktfmtFormat
   ./gradlew ktfmtCheck testDebugUnitTest lintDebug assembleDebug
   scripts/check-license-headers.sh
   ```

5. Commit with an SSH signature, push a branch, open a PR, wait for CI, and merge it with
   `gh pr merge <n> --merge`. `main` rejects direct pushes and unsigned commits.

6. Tag the merge commit on `main` with a signed tag:

   ```sh
   git switch main
   git pull --ff-only
   git tag -s v<version> -m "v<version>"
   git tag -v v<version>
   git push origin v<version>
   ```

7. Watch the release:

   ```sh
   gh run list --workflow=release.yml --limit 1
   gh run watch <run-id> --exit-status
   ```

   The important jobs are `Build signed APKs`, `Check the build reproduces` and
   `Publish GitHub release`. Verify the release page has the APKs, `SHA256SUMS` and the SBOM.

## When "Check the build reproduces" fails

Download both sides before guessing:

```sh
gh run download <run-id> -n reproduce-<tag> -D /tmp/repro
gh release download <tag> -p '*.apk' -D /tmp/release
```

Compare the released APK with the rebuilt unsigned one:

```sh
apksigcopier compare /tmp/release/simplexray-<tag>-arm64-v8a.apk \
  --unsigned /tmp/repro/app-arm64-v8a-release-unsigned.apk
```

If `apksigcopier` fails before comparing, diff the ZIP entries and the native binaries directly.
Common causes, in order of likelihood:

- **Wrong NDK.** A runner exports `ANDROID_NDK_HOME`; if it is not the NDK in `version.properties`,
  `libxray.so` differs. Check `readelf -p .comment` — the clang version must match the pinned NDK.
  The reproduce job and `scripts/verify-reproducible.sh` must export the pinned NDK.
- **Wrong Go or linker flags.** `GO_VERSION` must match; Xray on Android needs
  `-checklinkname=0` for `wlynxg/anet` on Go 1.27+.
- **Locale-dependent native build.** `hev-socks5-tunnel` uses a locale-sensitive wildcard; the
  build overrides it with a byte-sorted list. See the PR that fixed `v1.11.0-rc1`.
- **Real source difference.** Compare ZIP entry CRCs and ELF sections to identify the file.

Fix the cause in a PR, then cut a **new** version. Pushed `v*` tags are immutable; never try to
move or re-push one. A failed build leaves an unreleased tag and the next version skips a number —
that is expected, not a reason to delete the old tag.

## Tag and automation notes

- Manual release tags must be SSH-signed (`git tag -s`) and verified (`git tag -v`).
- The automated `Update Xray-core` workflow creates lightweight API tags; its commits are
  GitHub-verified. If SSH-signed automation tags are required, it needs a dedicated signing-key
  secret and a `git tag -s` + push step.
- The updater builds and tests the updated tree before tagging (see `simplexray-xray-update`).
  The signed/reproducible build still runs after the tag, so only a pre-tag build is guaranteed.

Keep local paths, keystore filenames, aliases and fingerprints out of commits and notes.
