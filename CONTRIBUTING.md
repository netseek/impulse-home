# Contributing

Thanks for helping. This app runs on a head unit with a slow main thread, so the
rules below are about not making it slower.

## Read first

[`AGENTS.md`](AGENTS.md) holds the ground rules (written for AI agents, and just as good for people),
and the [engineering notes](docs/engineering/measuring-and-performance.md) hold the hard-won facts.
Their one rule applies to every performance change: **measure on the car (or say that you could not) before
and after.** Several plausible optimisations in it were undone by a single
measurement.

## Build

Requirements: JDK 21, the Android SDK (platform 36), Node 22.

```bash
node scripts/fetch-assets.mjs     # 3D models and images; needs access, see below
./gradlew assembleDebug testDebugUnitTest
node scripts/run-tests.mjs        # Node contract tests
```

### About the assets

The 3D models and images are not in this repository (see `LICENSE-ASSETS`).
`fetch-assets.mjs` downloads them for the maintainer and for CI. **You can still
build and test without them:** the code compiles and every test runs, but the app
starts without the car and its images. If you need to see the UI, supply your own
models under `assets/` with the same file names.

## Pull requests

* Keep a PR to one change. Describe how you verified it.
* Sign off every commit (`git commit -s`). That adds a `Signed-off-by:` line and
  means you certify the [Developer Certificate of Origin](https://developercertificate.org/):
  you wrote the change, or have the right to submit it under this project's license.
* Code is AGPL-3.0, like the rest of the repository. Do not submit code you do not
  have the right to relicense.
* A signal that arrives from the vehicle bus must never reach React `setState`;
  route it through the `_live*` seam (explained in the
  [engineering notes](docs/engineering/measuring-and-performance.md)).
* Contract tests live in `scripts/test-*.mjs`. A test listed in
  `scripts/tests-known-failing.txt` is known debt; fixing one is a welcome PR.
* Do not add 3D models, textures or images to a PR. Open an issue first: assets
  are licensed separately and need a provenance record.
