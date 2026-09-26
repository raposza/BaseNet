<!-- Copyright (c) 2026 bentzn -->
<!-- SPDX-License-Identifier: Apache-2.0 -->
# AGENTS.md

Operating notes for anyone - person or agent - changing this repository.
`README.md` says what BaseNet is and `README_LONG_TIME.md` is the full
description; this file is how to work in the tree.

## Commands

```
cp basenet.conf.local.example basenet.conf.local   # your values; never committed
./shared/render.sh                  # the gate: renders every chart offline, installs nothing
./shared/oidc.sh                    # the bundled identity provider; stays up
./run-validator.sh                  # the BaseNet Validator, end to end
./run-member.sh memberorg-a         # one BaseNet Member on it
./validator/smoke.sh                # asserts a founded BaseNet Validator
python3 shared/oidc_check.py <issuer>   # an identity provider against what Splice needs
```

## Four words, never interchanged

* **BaseNet** - the whole thing: one BaseNet Validator and the BaseNet Members
  connected to it.
* **BaseNet Validator** - the complete network-side infrastructure, the half
  that runs outside a company: super validator, sequencer, mediator,
  participant, scan and the supporting services. It is NOT a Splice validator
  node, which is a different thing wearing the same word.
* **BaseNet Member** - the DEPLOYMENT a company runs, the same one it would run
  against Dev-, Test- or MainNet: participant, validator app, wallet and
  name-service UIs, PostgreSQL - pointed at a BaseNet Validator instead.
* **MemberOrg** - the ENTITY that owns and uses a BaseNet Member. Acme Bank is
  a MemberOrg; what Acme Bank runs is a BaseNet Member.

One BaseNet Validator with two BaseNet Members connected, one owned by Acme
Bank and one by Acme Broker, is two MemberOrgs. In prose a MemberOrg is never a
node, a deployment or a namespace. The IDENTIFIERS keep the word -
`memberorg-*.sh`, `values/memberorg/`, `STR_MEMBERORG`, `STR_MO_*`, the
namespace `memberorg-a`.

## Conventions

* **Every script sources `basenet.conf`, then `basenet.conf.local` when it
  exists, and nothing else is configurable.** A new knob goes in
  `basenet.conf` with a comment saying what it is for; the `.local` file is how
  a user's real values stay out of the tree, and `.gitignore` names it. Never
  hardcode an address, a version or a password into a script or a values file.
* **`shared/render.sh` is the gate and it must stay offline.** It contacts no
  cluster. Run it after every edit to a values file and after every version
  bump.
* A comment carries its reason. What must never appear is a fact about
  somebody's machine or organization: a host name, an address, a credential, a
  path from anyone's workstation.
* Shell: `set -euo pipefail`, `cd "$(dirname "$0")"` first, functions before
  use. Variable naming is reverse-common: `strToken`, not `tokenStr`.
* File header: copyright line, SPDX identifier, author. No generated
  timestamp - a public file carries none.

## The two things that must never silently break

`validator/values/sv.yaml` must not acquire a `joinWithKeyOnboarding` key. Its
presence selects the join-with-key branch of the SV chart, and this whole
repository exists to reach the other branch. Its absence is what founds the
network.

`domain.sequencerPublicUrl` in the same file is advertised into the topology
when the DSO is founded and cannot be corrected afterwards. Changing it means
founding the network again - so on any cluster where a Member will not sit
beside the Validator, it must carry an externally reachable name BEFORE the
first install.

## The release table is in three places

`validator/install.sh`, `shared/render.sh` and `shared/images.sh` each carry
the same nine chart/release/values rows. A chart added to one must be added to
all three. This is the one duplication in the repository and it is the thing
most likely to drift.

`shared/memsweep.py` carries a fourth partial copy: the release name and the
values file of every component it sizes. `shared/nmt_overlay.py` carries a
fifth: the release, the chart, the resources path and the measured non-heap
reserve of every component it sizes.

The Member side has its own three rows, in `member/memberorg-install.sh` and in
`nmt_overlay.py`'s `--member` table, and nowhere else. `render.sh` renders them
by calling `memberorg-install.sh --render`, so there is no second copy of the
install side to drift. **A Member's release names collide with the
Validator's** - both have `participant` and `validator` - so the two are
separate tables, and an overlay written for one must never be passed to the
other: the release name would match and the wrong pod would be sized.

`member/memberorg-teardown.sh` deletes a namespace only when it carries the
`basenet-member=true` label `memberorg-secrets.sh` sets. Keep that guard:
without it a mistyped name deletes whatever namespace it happens to name.

## The identity provider

`shared/oidc.sh` starts Raposza OIDC, a separate project this repository
does not build. It takes the newest `raposza-oidc-server-*-app.jar` in the
local Maven repository and keeps its keys, users and clients in
`STR_OIDC_DIR`, so a BaseNet never shares a key set or a client registry with
anything else on the machine. The script keeps its old name because every
document and habit calls it that.

It is an OpenID Provider for a test system and NOT a replacement for Keycloak.
Its people's grants - the login page, `authorization_code` with PKCE,
`refresh_token` - are checked as the specifications require; its machine grant,
`client_credentials`, checks nothing while no client is registered, and none
is. `shared/oidc_check.py` must pass against it after any change of version.

Its issuer must never be left to be derived per request. Discovery and every
token must carry the same issuer string, compared literally, and a derived one
disagrees the moment a caller on the host and a caller inside the cluster both
use it. `shared/oidc.sh` pins it.

## Version bumps

One release of this repository targets one Splice release. When the version in
`basenet.conf` moves: run `shared/render.sh` first and read every schema
failure it reports, because the required set changes between releases and the
failures are the change list.
