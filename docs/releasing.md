# Releasing

## One-time setup

1. Create a release keystore, and keep it and its passwords somewhere safe.
   Losing it means users can no longer update in place.

   ```sh
   keytool -genkeypair -v -keystore simplexray-release.jks -alias simplexray \
     -keyalg RSA -keysize 4096 -validity 10000
   ```

2. In **Settings → Environments**, create an environment named `release`.
   Restrict its deployment branches and tags to `main` and `v*`.
3. Add these environment secrets:
   - `SIMPLEXRAY_KEYSTORE_BASE64`: the output of `base64 -w0 simplexray-release.jks`
   - `SIMPLEXRAY_KEYSTORE_PASSWORD`
   - `SIMPLEXRAY_KEY_PASSWORD` (optional; defaults to the store password)
4. Add these repository variables:
   - `SIMPLEXRAY_KEY_ALIAS`, if the alias is not `simplexray`
   - `SIMPLEXRAY_CERT_SHA256`: the certificate's SHA-256 (from
     `keytool -list -v`, without colons). Builds signed with any other key
     then fail.
5. In **Settings → Actions → General**, give workflows read and write
   permissions. The Xray-core update workflow commits and tags with the
   workflow token, so `main` must accept pushes from GitHub Actions.

Until the keystore secret exists, CI skips the signed build on `main`, and the
release workflow fails with a message pointing here.

## Cutting a release

1. Bump `APP_VERSION_CODE` and `APP_VERSION_NAME` in `version.properties`.
2. Write `docs/release-notes/v<version>.md`.
3. Commit, then tag and push:

   ```sh
   git tag -a v1.11.0 -m v1.11.0
   git push origin main v1.11.0
   ```

`.github/workflows/release.yml` then does the following:

- builds signed APKs (per ABI and universal) and checks their certificate;
- writes `SHA256SUMS` and an SPDX SBOM;
- attests every file with Sigstore;
- publishes the GitHub release, using the release-notes file as its
  description.

At the same time, a second job rebuilds the tag from scratch and checks that
the APKs match. Tags with a suffix (`v1.11.0-rc1`) are published as
pre-releases.

## Automatic Xray-core updates

Every night, `.github/workflows/update-xray.yml` checks for a new Xray-core
release. If there is one, it does the following:

- bumps Xray-core, Go (to the newest patch of the release Xray-core needs) and
  the geo data;
- bumps the app's patch version;
- writes release notes, commits, and tags;
- starts the release workflow.

To refresh Go and the geo data without a new Xray-core, run the workflow by
hand with **force**.

## Verifying a release

```sh
gh attestation verify simplexray-v1.11.0-arm64-v8a.apk -R franticplant/simplexray-custom
scripts/verify-reproducible.sh v1.11.0
```
