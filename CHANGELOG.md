<!-- Copyright (c) 2026 bentzn -->
<!-- SPDX-License-Identifier: Apache-2.0 -->
# Changelog

Version numbers here are this repository's own. The Splice and Canton releases
BaseNet stands up are chosen in `basenet.conf` and move independently of them.

## 0.3.0 - 2026-09-26

### Added

* **`onboarding.json`** beside `run-member.sh` points a BaseNet Member at
  another network and onboards it there with that network's secret: its
  addresses, its Splice release and the secret come from the file, the
  secret into a Kubernetes secret named by `onboardingSecretFrom`.

### Changed

* **`jwtmint` is gone by name.** `shared/jwtmint.sh` is `shared/oidc.sh`, and
  `STR_JWTMINT_USERS` is `STR_OIDC_USERS`. The provider is Raposza OIDC, as
  below.
* **The bundled identity provider is Raposza OIDC.** `shared/oidc.sh` runs
  the newest `raposza-oidc-server-*-app.jar` in the local Maven repository,
  on its own store `STR_OIDC_DIR` - `~/.raposza/basenet/oidc` by default - and
  refuses `--build`. No client is registered in that store.

* **Splice 0.8.3.** `STR_SPLICE_VERSION` is 0.8.3, and the BaseNet
  Validator's sequencer runs CantonBFT, one node and no peers -
  `validator/values/global-domain.yaml`, with `domain.enableBftSequencer` in
  `validator/values/sv.yaml`. From Splice 0.8.0 the global-domain chart has no
  `postgres` sequencer driver, and `type: "postgres"` renders a sequencer with
  no driver at all. **These values do not serve Splice 0.7.x** - use 0.2.0
  for that.
* The four PostgreSQL releases run the `splice-postgres` chart's own default
  image, `postgres:14.24-trixie` at 0.8.3; the init containers keep
  `postgres:14`.
* The mock KMS driver carries the repository version, 0.3.0.

### Fixed

* `shared/oidc_check.py` ran its code-reuse test on the code whose tokens its
  refresh and UserInfo checks then used. A provider that revokes a reused
  code's tokens, as RFC 6749 section 4.1.2 recommends, failed three checks it
  passes. The reuse test now spends a code of its own, and reports the
  revocation itself - PASS when the tokens are revoked, WARN when they are not.

### Removed

* `shared/jwtmint/`, the provider this repository built.

## 0.2.0 - 2026-09-14

### Added

* **A mock KMS driver for the BaseNet Member** - `shared/mockkms/`, a Canton
  KMS Driver v1 that keeps its keys in a plaintext file so a participant can
  restart without losing the identity it founded. It is OFF by default:
  `FLAG_MO_KMS=1` and the five `STR_MO_KMS_*` settings in `basenet.conf` turn
  it on, and the jar reaches the participant through a Kubernetes secret and
  `EXTRA_CLASSPATH` - no custom image and no registry.
  **The private keys are written unencrypted and are readable by anyone who
  can read the file. This is for testing and nothing else.**
* `member/values/participant-kms.yaml`, the install overlay that mounts the
  driver, and the matching branches in `member/memberorg-secrets.sh` and
  `member/memberorg-install.sh`.
* `loadtest/ModerateLoadTesting_20260915.md` - four hours and 57,600
  transactions at 4.00 per second against a KMS-backed Member: zero failures,
  no pod restart, and no measurable latency or memory cost against the same
  run without the driver.

### Changed

* `jwtmint` and the mock KMS driver both carry the repository version.

## 0.1.0 - 2026-09-14

First public release.

* `run-validator.sh` and `run-member.sh` stand up the two halves end to end on
  your own Kubernetes cluster, from the vendor's own Helm charts;
  `run-optimized.sh` runs either side with an absolute heap and a pod limit
  calculated from it.
* `shared/jwtmint` - the OpenID Provider the network's tokens come from;
  `shared/ui.py` serves the wallet and name-service UIs.
* `member/run-loadtest.sh` - a paced load run with a memory sampler beside it,
  and the first report under `loadtest/`.
* `docs/` - prerequisites, images, the auth model and a security review.
