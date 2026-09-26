<!-- Copyright (c) 2026 bentzn -->
<!-- SPDX-License-Identifier: Apache-2.0 -->
# Raposza BaseNet

A disposable, private Canton Network environment for validating a Splice
release before it reaches your normal network progression.

**Test tomorrow's Splice release today.**

This repository is deliberately small and deliberately boring. It is a set of
Helm values files and shell scripts. It introduces no proprietary
protocol, service, API or runtime dependency, it contains no vendor artifact,
and it pulls the Splice charts from the vendor's own registry at install time.
Read it, change it, run it internally, stop using it whenever you like.

## What it gives you

Two halves, both from the vendor's own charts.

**BaseNet Validator** stands in for the rest of the network: a complete Canton
Network with **no external super validator** - sequencer, mediator,
participant, scan, SV and validator, founding their own DSO on your own
Kubernetes cluster, on four throwaway PostgreSQL instances. It comes up, you
use it, you destroy it.

**BaseNet Member** is your side of it: a plain Splice validator node, deployed
the way an organization deploys one against DevNet, TestNet or MainNet, pointed
at the BaseNet Validator instead - see below. The organization that owns a
BaseNet Member is a **MemberOrg**; a BaseNet is one BaseNet Validator and the
BaseNet Members connected to it.

One release of this repository targets one Splice release. The version is in
`basenet.conf` and it is the only version this tree has been exercised
against.

## What it is not

With a local SV and no public connection, the amulet economy, round state and
governance are entirely local. This is good for compatibility testing, for
integration work and for demonstrations. It tells you nothing about traffic
costs, SV voting or real onboarding, and it must not be used to claim anything
about them.

## Requirements

* A Kubernetes cluster you can throw away. Single-node k3s is enough - see
  `docs/prerequisites.md`, which includes the one k3s installation flag that
  matters.
* `helm`, `kubectl` and `python3` on the machine you run these scripts from.
* An OIDC provider the cluster can reach, for the SV and validator apps.
  `shared/oidc.sh` starts Raposza OIDC from your local Maven repository
  (needs Java 21 and its `raposza-oidc-server-*-app.jar`) so you do not have to
  stand up Keycloak to try this; your own works equally well - see
  `docs/auth.md`.

## Use

```
cp basenet.conf basenet.conf.local      # then edit the .local copy
./shared/render.sh          # the gate: renders every chart, contacts no cluster
./shared/oidc.sh         # the identity provider; STAYS UP, use another terminal
./run-validator.sh          # secrets, the nine Helm releases, then the smoke test
./run-validator.sh --round  # the same, and wait for the first closed round
( cd validator && ./teardown.sh )   # destroys the namespace, keeps the image cache
```

`run-validator.sh` is `validator/secrets.sh`, `validator/install.sh` and
`validator/smoke.sh` in order, and then it prints where the UIs are. Run them
one at a time if you would rather.

`STR_OIDC_BASE_URL` in `basenet.conf` is the address the CLUSTER reaches the
mint on, and it is pinned as the issuer. Set it to a host address, not
localhost: inside a pod, localhost is that pod.

## A BaseNet Member on this network

A MemberOrg - a bank, or any other organization - joins DevNet, TestNet or
MainNet with a validator node: its own participant, a validator app with the
wallet and name-service UIs, and PostgreSQL. The `memberorg-*.sh` scripts
deploy exactly that, from the vendor's own charts, pointed at the BaseNet Validator
instead; that deployment is a BaseNet Member. Each one gets its own namespace:

```
./run-member.sh memberorg-a                     # the namespace, the secrets, the three releases
cd member
./memberorg-install.sh --render memberorg-a     # what would be installed; contacts no cluster
./memberorg-teardown.sh memberorg-a             # destroys that BaseNet Member, and refuses anything else
```

The name is the MemberOrg's namespace, its validator's party hint and its node
identifier. A second BaseNet Member is the same commands with another name.

Three keys in `basenet.conf` point a BaseNet Member at a network:
`STR_EXT_SCAN_URL`, `STR_EXT_SPONSOR_URL` and `STR_EXT_SEQUENCER_URL`. Left
blank they reach the BaseNet Validator from another namespace on the same cluster. A
BaseNet Member on another cluster sets addresses that cluster can reach - and
`domain.sequencerPublicUrl` in `validator/values/sv.yaml` must be one of them before
the BaseNet Validator is founded, because the network advertises it and it cannot be
changed afterwards.

Onboarding needs no secret: this network's SV is founded with
`isDevNet: true`, and a validator that names a sponsor and no secret takes the
chart's DevNet onboarding path. That rehearses the mechanics of joining. With
one local SV it says nothing about sponsorship, allowlists or approval on a
real network.

## Opening the web UIs

```
python3 shared/ui.py                # every namespace; STAYS UP, Ctrl-C ends it
python3 shared/ui.py memberorg-a    # one namespace
```

The vendor's web UIs run as pods that serve static pages, and each page calls
its backend on its own address. In production an ingress routes those calls;
`ui.py` does the same on this machine, one port per namespace from
`STR_UI_PORT`, and prints the addresses:

```
  sv           wallet  http://wallet.localhost:4400/
  sv           sv      http://sv.localhost:4400/
  sv           scan    http://scan.localhost:4400/
  memberorg-a  wallet  http://wallet.localhost:4401/
  memberorg-a  ans     http://ans.localhost:4401/
```

A name under `.localhost` reaches this machine in Chromium, Brave and Firefox
without a hosts-file entry. Sign in to a BaseNet Member's wallet as that
MemberOrg's user - `memberorg-a-user` with the bundled provider, its password in
`STR_OIDC_USERS` - at the identity provider's own login page.
the BaseNet Validator's wallet and name-service UIs keep the vendor's test login, which
asks for a name only, because the BaseNet Validator's nodes keep `disableAuth`.

## Running it without direct access to the vendor's registry

The Helm charts and the container images come from two registries, and neither
is carried in this repository. Both are reachable through your own:

* `STR_CHART_REPO` in `basenet.conf` points Helm somewhere else. An
  Artifactory remote repository proxying the vendor's needs nothing further.
`docs/images.md` covers pointing at your own registry, including a mirror
that does not keep the vendor's image names.

* `./shared/images.sh` prints every image reference
  version, derived from the rendered manifests rather than from a hand-kept
  list, which is wrong the first time a chart adds a sidecar. Feed it to
  whatever mirrors into your registry; the script header shows the
  pull/tag/push loop.
* `STR_IMAGE_REPO` in `basenet.conf` then points every image at your mirror.

At 0.8.3 that is ten Splice images, all digest-pinned, plus two PostgreSQL images by tag. Note
the last one: `splice-postgres` renders `imageName` as the whole reference and
its `imageRepo` does not reach it, so PostgreSQL comes from Docker Hub unless
`STR_IMAGE_REPO` is set - `STR_PG_IMAGE` carries the registry for that one.

Neither the charts nor the images are redistributed here, so mirroring them is
your side of the vendor's licence, not this repository's.

`shared/render.sh` is worth running on its own. Every Splice chart ships a
`values.schema.json` and Helm enforces it, so a values file that would fail
twenty minutes into an install fails here instead, in seconds, against nothing.

The DSO is not founded when `install.sh` returns. It is founded when `sv-app`
answers its readiness endpoint, which is several minutes later. `smoke.sh`
waits for that and for scan to answer, and exits non-zero naming the step that
did not happen:

```
./run-validator.sh
```

The network is usable at that point; it does not need a closed mining round.
`./run-validator.sh --round` also waits for the first round to close, which on a new
network takes between half an hour and an hour.

## Licence

Apache-2.0. See `LICENSE` and `NOTICE`.

## Memory

`values/` on both sides ships the chart's own sizing, which is generous. An
install with an absolute heap per JVM and a pod limit calculated from it:

```
./run-optimized.sh validator
./run-optimized.sh member memberorg-a
```

The numbers are at the top of `run-optimized.sh` and they are this
repository's own measurements, not a universal sizing. The overlay is written
outside the tree, so an install without `--overlay` restores the shipped
sizing.

```
python3 shared/jvm_read.py --ns sv --gc       heap, non-heap and NMT, now
python3 shared/jvm_watch.py --ns sv           the same on an interval, peaks
```

## The load test

A paced concurrent workload on one Member's own participant, over the JSON
Ledger API, with every JVM in its namespace sampled from outside while it runs:

```
cd member
./petshop/build.sh                  builds the fixture DAR
./run-loadtest.sh memberorg-a       the run: latency, errors, memory peaks
```

`loadtest/ModerateLoadTesting_20260914.md` reports a ten-hour run of it:
144,000 transactions, no failures, and the memory behaviour hour by hour.

The workload is the pet shop fixture in `member/petshop/`, four transactions a
cycle, and its active contract set grows for the length of the run. The
synchronizer caps a member at 300 transactions per 60 seconds, so a single
member cannot be driven past about 5 transactions a second whatever the rate
asks for.

## Layout

```
basenet.conf   every setting; copy it to basenet.conf.local and edit that
shared/        the identity provider, the UI proxy, the image and memory tools
validator/     the BaseNet Validator: values, secrets, install, smoke, teardown
member/        a BaseNet Member: values, install, teardown, the load test
docs/          prerequisites, the auth model, the security posture
SECURITY.md    the reporting channel and the short statement of the model
```

## Working in this tree

`AGENTS.md` holds it: the four words that are never interchanged, the
conventions, the two settings that must never silently break, the release
table kept in three places, the identity provider and version bumps.
