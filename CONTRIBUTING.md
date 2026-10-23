# Contributing

Bug reports, fixes and improvements are welcome. For anything larger than a
small fix, open an issue first so we can agree on the approach.

## Reporting bugs

Use the bug report template. Include the app version, the Android version and
device, what you expected and what happened. Logs help: the app's log view,
or `adb logcat`. Strip real server addresses, UUIDs and keys from configs and
logs before attaching them.

Security problems go through [SECURITY.md](SECURITY.md), not public issues.

## Building

See [docs/building.md](docs/building.md) for the toolchain and native
components.

## Pull requests

- Keep each pull request to one change, and say in the description why it is
  needed.
- Run the checks CI runs before pushing:

  ```sh
  scripts/check-license-headers.sh
  ./gradlew ktfmtFormat ktfmtCheck testDebugUnitTest lintDebug assembleDebug
  ```

- New Kotlin and Java files start with the license header:

  ```kotlin
  // Copyright (c) SimpleXray contributors
  // SPDX-License-Identifier: MPL-2.0
  ```

- Lint treats every warning as an error. Fix findings rather than suppressing
  them; if one is truly a false positive, suppress it where it occurs, with a
  comment saying why.
- User-visible text goes in `app/src/main/res/values/strings.xml`, not in
  code.
- Add or update unit tests for logic you change.
- Commits on `main` must be signed. Sign yours
  ([GitHub's guide](https://docs.github.com/en/authentication/managing-commit-signature-verification/signing-commits)).

## License

By contributing, you agree that your contributions are licensed under the
[Mozilla Public License 2.0](LICENSE), like the rest of the project.
