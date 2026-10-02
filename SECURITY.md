# Security

## Reporting a vulnerability

Please report it privately through GitHub: **Security -> Report a vulnerability**
on this repository. Do not open a public issue for something exploitable.

## Installing safely

This app runs with access to the vehicle's data bus through Impulse, so only
install builds you can trace to this repository.

* Official builds are the `impulse-home.apk` files on the **Releases** page.
* Every release is signed with a single release key that exists only as a CI
  secret. The certificate SHA-256 is published in `latest.json` on every
  release; compare it before installing by hand:

  ```bash
  apksigner verify --print-certs impulse-home.apk
  ```

* `latest.json` also carries the APK's own SHA-256 so that an installer can
  verify the download before installing it.
* An APK signed with any other key is not an update of this app and will not
  install over it.

## What is not secret

The repository contains no signing key, token or password. If you find one,
that is a vulnerability: please report it.
