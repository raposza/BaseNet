<!-- Copyright (c) 2026 bentzn -->
<!-- SPDX-License-Identifier: Apache-2.0 -->
# Changelog

Version numbers here are this repository's own. The Splice and Canton releases
BaseNet stands up are chosen in `basenet.conf` and move independently of them.

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
