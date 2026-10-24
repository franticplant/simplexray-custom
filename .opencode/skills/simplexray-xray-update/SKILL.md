---
name: simplexray-xray-update
description: Update Xray-core, Go or geo data in simplexray-custom, or debug the nightly Update Xray-core workflow. Use whenever the task mentions XRAY_CORE_VERSION, GO_VERSION, GEOIP_VERSION/GEOSITE_VERSION, scripts/build-xray.sh, "invalid reference to net.zoneCache", GraphQL createCommitOnBranch, the update-xray workflow, or a version number that was skipped after a failed build.
---

# Updating Xray-core in simplexray-custom

`.github/workflows/update-xray.yml` runs nightly (or on `workflow_dispatch` with `force=true`).
It reads the latest Xray-core release, the Go minor from that release's `go.mod`, the latest geo
data, bumps `version.properties`, commits to `main`, tags the release and dispatches
`release.yml`.

## Version and tag behaviour

- `APP_VERSION_CODE` is incremented and the last component of `APP_VERSION_NAME` is bumped:
  `1.12.0 → 1.12.1 → 1.12.2`.
- If a release build fails, the tag already exists and cannot be moved. Fix forward; the next
  updater run bumps again. A skipped number therefore means a failed/unreleased tag, not a lost
  release.
- The workflow creates lightweight tags through the REST API. The commit is GitHub-verified; the
  tag itself is not SSH-signed. Add a dedicated signing key if that is required.

## Before tagging: the build must pass

The updater runs `.github/actions/setup-build` and then:

```sh
./gradlew --no-configuration-cache testDebugUnitTest assembleRelease -Punsigned
```

on the updated working tree **before** the commit/tag step. This catches link, compile and unit
test failures while the version can still be fixed. Do not remove this step or move it after the
tag. The checkout must use `submodules: recursive`.

## GraphQL commit gotcha

`gh api graphql --input -` replaces the whole request body, so `-f query=...` is ignored. Build the
complete body, including the query, with `jq` and pipe it to `--input -`. Keep the
`expectedHeadOid` equal to the current `HEAD`; if `main` moves, the commit fails rather than
overwriting someone else's work.

## Xray build flags

`scripts/build-xray.sh` builds `libxray.so` with the pinned NDK and:

```sh
-trimpath -buildvcs=false \
-ldflags="-X github.com/xtls/xray-core/core.build=<commit> -s -w -buildid= -checklinkname=0"
```

`-checklinkname=0` matches Xray's own Android build. Xray pulls in `wlynxg/anet`, which reaches
`net.zoneCache` through `//go:linkname`; Go 1.27's linker rejects that by default. Without the
flag, the build fails with:

```text
link: github.com/wlynxg/anet: invalid reference to net.zoneCache
```

If a future Xray release changes the required Go version, update `GO_VERSION` from the release's
`go.mod`; the updater already picks the newest patch of that minor. A brand-new Go patch can
change linker internals, so if a patch breaks the build, pin the exact patch that works and note
why.

## Manual run and verification

```sh
gh workflow run update-xray.yml --ref main -f force=true
gh run watch <run-id> --exit-status
```

Then confirm:

```sh
git fetch --tags origin
git log --oneline -2 origin/main
git show origin/main:version.properties
gh run list --workflow=release.yml --limit 1
```

The update run should end with a commit, a `v<version>` tag and a release run. Watch the release's
`Build signed APKs` and `Check the build reproduces` jobs.

## Debugging failures

- **Build Xray-core fails:** read that step's log, not the Gradle tail. Check the NDK revision
  (`Pkg.Revision` in `$ANDROID_NDK_HOME/source.properties`) against `NDK_VERSION`.
- **Linker errors:** check `-checklinkname`, Go version and whether upstream's Android build uses
  additional `-gcflags`/`-ldflags`.
- **Reproduce job fails:** see `simplexray-release` for artifact comparison and NDK checks.
- **GraphQL "query attribute" error:** the query is missing from the `--input` body; use the jq
  construction above.
- **Version/tag conflict:** a pushed `v*` tag is immutable; cut the next number, never force-push
  or delete a tag to reuse it.

Do not commit `.xray-src`, `app/src/main/jniLibs` or downloaded geo data; they are build outputs.
