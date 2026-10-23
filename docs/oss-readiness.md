# OSS-readiness ledger

What has been checked before publishing releases of this fork, and what is
still open. It records engineering findings and is not legal advice.

## 1. Publication posture

Ready for public, signed GitHub releases once the release key is configured
(section 6). There are no legal or trademark blockers: SimpleXray and
Xray-core are both MPL-2.0, and this fork does not present itself as the
original app.

## 2. License

- The repository is MPL-2.0 (`LICENSE`), inherited from lhear/SimpleXray.
  Every Kotlin source file carries an SPDX header naming "SimpleXray
  contributors", which covers the original authors and this fork alike. CI
  checks the headers (`scripts/check-license-headers.sh`).
- MPL-2.0 is file-level copyleft. Modified files stay MPL-2.0, and the source
  of each release is its tag.
- Bundled components and their licenses are listed in
  `THIRD_PARTY_NOTICES.md`. Each release also ships an SPDX SBOM of its Gradle
  dependencies.

## 3. Identity

| | Original | This fork |
| --- | --- | --- |
| Application ID | `com.simplexray.an` | `io.github.franticplant.simplexray` |
| Signing key | lhear's | the franticplant release key (section 6) |
| Source / update link | lhear/SimpleXray | franticplant/simplexray-custom |

The Kotlin namespace stays `com.simplexray.an`. hev-socks5-tunnel registers its
JNI natives on `com/simplexray/an/service/TProxyService`, and renaming the
package would only cause churn. The display name stays "SimpleXray", with the
README and store text saying it is an independent fork.

The icon, screenshots and the id/ru/zh store translations are inherited. The
icon and screenshots are MPL-2.0 material from the original repository. The
translations describe the same app and are accurate, but they don't mention
the fork; only `en-US` has been rewritten.

## 4. Secret scan (2026-10-21)

- `gitleaks git` over the full history (229 commits): no findings.
- Key files ever committed: only `debug.keystore`, the public Android debug key,
  which is intentional. `store.properties` (added in lhear's initial commit,
  removed in this fork) only ever held that key's public default credentials.
  An early `local.properties` held a Windows SDK path and nothing secret.
- Ongoing: `scripts/secret-scan.sh` as a pre-commit hook. GitHub secret
  scanning and push protection are enabled on the repository.
- Vulnerabilities are reported privately through GitHub's private
  vulnerability reporting (`SECURITY.md`).

## 5. Release integrity

- Releases are built only by `.github/workflows/release.yml`, from `v*` tags,
  in the `release` environment. That environment is limited to `main` and `v*`
  and holds the only copy of the signing secrets.
- Every APK's certificate is checked against `SIMPLEXRAY_CERT_SHA256`. APKs
  signed with the public debug key are refused.
- Every file gets SLSA build provenance and an SBOM attestation (Sigstore),
  plus `SHA256SUMS`.
- Builds are reproducible from the tag. A second runner rebuilds each release
  and compares it with apksigcopier. Anyone can repeat this with
  `scripts/verify-reproducible.sh`. v1.11.0 is the first full release to pass
  that check on GitHub's runners.
- Repository rules: `main` requires signed commits and allows no force-push or
  deletion; `v*` tags cannot be moved or deleted.

## 6. Signing key

The release key exists only as secrets of the `release` environment. Its
certificate's SHA-256 is pinned in the `SIMPLEXRAY_CERT_SHA256` repository
variable, and a release signed by any other key fails. The app declares no
signature-level permissions and no shared UID, so the key grants nothing
beyond the right to publish updates.

## 7. Open items

- Rewrite the id/ru/zh store descriptions for the fork (optional).
- targetSdk 37: adopt Android 17 behaviour changes after on-device testing.
