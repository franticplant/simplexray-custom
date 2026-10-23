# Security policy

## Reporting a vulnerability

Report vulnerabilities privately through
[GitHub's private vulnerability reporting](https://github.com/franticplant/simplexray-custom/security/advisories/new).
Please don't open a public issue for them.

Include the app version (Settings → About, or the release tag), the Android
version, and the steps or config that trigger the problem. Strip real server
addresses, UUIDs and keys from any config you attach.

You should get a first reply within a week. Fixes ship as a new release, and
the advisory is published once that release is out.

## Scope

In scope:

- this app's code: the VPN service, instance supervision, config handling and
  import, and the UI;
- the build and release pipeline: workflows, scripts, signing and
  attestations.

Report these upstream instead:

- Xray-core itself: [XTLS/Xray-core](https://github.com/XTLS/Xray-core/security);
- hev-socks5-tunnel: [heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel).

If a bug in one of them affects this app, tell us too, so a release can pick
up the fix.

## Supported versions

Only the latest release gets fixes. Pre-releases (`-rc` tags) are not
supported once the release they lead to is out.

## Verifying a release

Every release file has build-provenance and SBOM attestations, and the build
is reproducible from its tag. See [docs/releasing.md](docs/releasing.md).
