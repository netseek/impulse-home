# Releasing

Two things are published separately, on purpose:

| What | Where | Changes |
|---|---|---|
| **Code and the APK** | this repository, GitHub Releases | every release |
| **Asset bundle** (3D models, images) | private repo `netseek/haval-h6-assets`, as a release asset | only when the art changes |

`assets.lock.json` is the link between them: it names one bundle by version and
sha256, so a given commit always builds with the same art.

## One-time setup

### 1. The release signing key

Generate it **on your own machine**, once, and never commit it:

```bash
keytool -genkeypair -v -keystore impulse-home-release.jks -alias impulse-home \
  -keyalg RSA -keysize 4096 -validity 10000
```

Keep the `.jks` and its passwords in a password manager, with a second backup:
**if the key is lost, no installed car can ever update again** (Android refuses an
update signed by a different key; the app must be uninstalled first, which wipes
its settings).

Print the certificate fingerprint you will publish and pin:

```bash
keytool -list -v -keystore impulse-home-release.jks -alias impulse-home | grep SHA256
```

### 2. Repository secrets and variable

Settings -> Secrets and variables -> Actions:

| Name | Kind | Value |
|---|---|---|
| `KEYSTORE_BASE64` | secret | `base64 -w0 impulse-home-release.jks` |
| `KEYSTORE_PASSWORD` | secret | store password |
| `KEY_ALIAS` | secret | `impulse-home` |
| `KEY_PASSWORD` | secret | key password |
| `ASSETS_TOKEN` | secret | fine-grained PAT, **read-only** `Contents` on `haval-h6-assets` only |
| `RELEASE_SIGNER_SHA256` | variable | the fingerprint above, no colons; the workflow refuses to publish if the key differs |

### 3. Branch protection on `main`

Require a pull request, require the `tests` and `android` checks, block force
pushes. `CODEOWNERS` then makes you the required reviewer.

## Publishing new art

```bash
node scripts/pack-assets.mjs --version 1.1.0 --src <folder with the full assets/> --lock
gh release create assets-v1.1.0 dist/assets-1.1.0.tar.gz -R netseek/haval-h6-assets \
  --title "Assets 1.1.0" --notes "..."
git add assets.lock.json && git commit -m "assets: 1.1.0"
```

The bundle is everything under `assets/` except path segments starting with `_`
(aapt drops those from the APK anyway) and the entries in
`scripts/assets-exclude.txt`.

## Cutting a release

```bash
git tag v1.2.0 && git push origin v1.2.0
```

* `v1.2.0` is a stable release; `v1.2.0-preview.1` is a pre-release.
* `versionCode` is `major*10000 + minor*100 + patch` (1.2.0 -> 10200).
* The workflow refuses a tag that is not on `main`.
* It publishes `impulse-home.apk` and `latest.json`. The stable manifest is always at

  ```
  https://github.com/netseek/impulse-home/releases/latest/download/latest.json
  ```

  GitHub's `latest` skips pre-releases, so a preview channel needs its own URL.

## Updating on the car

Impulse reads `latest.json`, compares `versionCode`, downloads `apkUrl`, and
should check `sha256` and `signerSha256` before installing. (That check lives in
Impulse, not here.)
