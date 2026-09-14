<!-- Copyright (c) 2026 bentzn -->
<!-- SPDX-License-Identifier: Apache-2.0 -->
# Raposza BaseNet

A disposable Canton Network you stand up on your own Kubernetes cluster, use,
and destroy. It is for testing a Splice release before it reaches a network
you care about.

Two halves, both from the vendor's own Helm charts:

* **BaseNet Validator** - the network side, with no external super validator:
  SV, sequencer, mediator, participant, scan and their databases.
* **BaseNet Member** - your side: the validator node an organization deploys
  against Dev-, Test- or MainNet, pointed at the BaseNet Validator instead.

Nothing from the vendor is redistributed here. The charts and the images are
pulled from their own registries at install time.

## You need

A throwaway Kubernetes cluster, `helm`, `kubectl` and `python3`, plus Java 21
and Maven if you use the bundled identity provider. `docs/prerequisites.md`.

## Run it

```
cp basenet.conf.local.example basenet.conf.local   # then edit the copy
./shared/jwtmint.sh                  # the identity provider; stays up
./run-validator.sh                   # the network side, end to end
./run-member.sh memberorg-a          # one member on it
```

Both print where the web UIs are and which command shows what. For the sized
installs instead of the shipped ones: `./run-optimized.sh validator` and
`./run-optimized.sh member memberorg-a`.

## Layout

```
basenet.conf   every setting there is
shared/        the identity provider, the UI proxy, the image and memory tools
validator/     the BaseNet Validator
member/        a BaseNet Member, and the load test
docs/          prerequisites, the auth model, the security posture
```

`SECURITY.md` is the reporting channel and the short statement of the model;
`docs/security-review.md` is the full posture document, and it is worth reading
before you decide where to run this.

`README_LONG_TIME.md` is the full description, and it is where to start if
you are going to change anything: what this is not, mirroring into your own
registry, the auth model, the load test, the terminology, and the two settings
that cannot be corrected once a network is founded.

## Licence

Apache-2.0. See `LICENSE` and `NOTICE`.
