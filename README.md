<!-- Copyright (c) 2026 bentzn -->
<!-- SPDX-License-Identifier: Apache-2.0 -->
# Raposza BaseNet

The missing link: a Canton Network that runs without contact to the outside
world, with zero dependencies on the ever-changing versions of the three
connected networks - MainNet, TestNet and DevNet.

It is where you deploy your new application for staging, QA, internal demos
and the like. Use it to test a new Splice release with your customizations
before you do it in public on DevNet, and to share development and debugging
internally. Use Raposza OIDC for auth, or use your organization's own
identity provider.

In contrast to ScratchNet, where every node runs together in one cluster,
BaseNet is split in two parts, both from the vendor's own Helm charts:

* **BaseNet Member** - your half, the equivalent of what you run against
  MainNet, TestNet and DevNet.
* **BaseNet Validator** - the super validator half: SV, sequencer, mediator,
  participant, scan and their databases.

Nothing from the vendor is redistributed here. The charts and the images are
pulled from their own registries at install time.

## You need

A throwaway Kubernetes cluster, `helm`, `kubectl` and `python3`, plus Java 21
and Raposza OIDC's server jar in your local Maven repository if you use the
bundled identity provider. `docs/prerequisites.md`.

## Run it

```
cp basenet.conf.local.example basenet.conf.local   # then edit the copy
./shared/oidc.sh                  # the identity provider; stays up
./run-validator.sh                   # the network side, end to end
./run-member.sh memberorg-a          # one member on it
```

Both print where the web UIs are and which command shows what.

**To join a network someone else runs** - Raposza SV, say - put the
`onboarding.json` it gives you in this directory, beside `run-member.sh`,
and run `./run-member.sh <name>`. The Member takes that network's addresses,
its Splice release and its onboarding secret from the file; nothing in
`basenet.conf` needs changing. The network must have whitelisted the address
your machine reaches it from. For the sized
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

`README_LONG_TIME.md` is the full description: what this is not, mirroring
into your own registry, the auth model and the load test. `AGENTS.md` is where
to start if you are going to change anything: the terminology, the conventions,
and the two settings that cannot be corrected once a network is founded.

## Licence

Apache-2.0. See `LICENSE` and `NOTICE`.
