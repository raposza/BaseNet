<!-- Copyright (c) 2026 bentzn -->
<!-- SPDX-License-Identifier: Apache-2.0 -->
# Images, and pointing at your own registry

## How a reference is built

Every Splice chart composes a container image reference as

```
<imageRepo>/<imageName>:<tag>
```

`imageRepo` is one top-level key per chart, defaulting to the vendor's path.
`imageName` is per component, and several charts have more than one. The tag
comes from the chart version.

`STR_IMAGE_REPO` in `basenet.conf` sets `imageRepo` for every chart at once. If
your mirror holds the images under the vendor's own names, that single setting
is the whole job:

```
STR_IMAGE_REPO=registry.example.com/basenet
```

`./shared/images.sh` prints the list to mirror, derived from the rendered
manifests rather than from a hand-kept table, and `--as-deployed` prints it
again with your settings applied. `./shared/download_images.sh <version>` pulls
each one, records the digest it arrived with, and writes `SHA256SUMS` over the
saved tars.

## When the mirror does not keep the vendor's names

`imageName` is an ordinary string and may contain slashes, so an image can be
moved anywhere in a registry by overriding its own key. `STR_IMAGE_OVERRIDE`
carries those overrides:

```
STR_IMAGE_OVERRIDE="participant:imageName=platform/canton-participant
                    scan:ui.imageName=web/scan-web-ui
                    *:persistence.initImageName=infra/postgres:14"
```

Each entry is `<release>:<chart key>=<value>`, whitespace separated, no
whitespace inside a value. **The release is the install name, not the chart.**
`*` matches every release.

An override is passed after the release's own values file, so it wins. A key
the chart does not declare **fails the render** - the charts set
`additionalProperties: false` - which is why `./shared/render.sh` is worth
running before any install: it reports a wrong key without contacting a
cluster.

## Every image key the charts publish

Release names are the BaseNet Validator's; a BaseNet Member's three releases
are `postgres`, `participant` and `validator` in its own namespace.

| release | chart | key | vendor default |
| --- | --- | --- | --- |
| `sequencer-pg`, `mediator-pg`, `participant-pg`, `apps-pg` | `splice-postgres` | `imageName` | `postgres:14.24-trixie` at 0.8.3; `postgres:14` at 0.7.x |
| `global-domain-0` | `splice-global-domain` | `sequencer.imageName` | `canton-sequencer` |
| `global-domain-0` | `splice-global-domain` | `sequencer.cometbft.imageName` | `canton-cometbft-sequencer` |
| `global-domain-0` | `splice-global-domain` | `sequencer.persistence.initImageName` | `postgres:14` |
| `global-domain-0` | `splice-global-domain` | `mediator.imageName` | `canton-mediator` |
| `global-domain-0` | `splice-global-domain` | `mediator.persistence.initImageName` | `postgres:14` |
| `participant` | `splice-participant` | `imageName` | `canton-participant` |
| `participant` | `splice-participant` | `persistence.initImageName` | `postgres:14` |
| `scan` | `splice-scan` | `imageName` | `scan-app` |
| `scan` | `splice-scan` | `ui.imageName` | `scan-web-ui` |
| `scan` | `splice-scan` | `persistence.initImageName` | `postgres:14` |
| `sv` | `splice-sv-node` | `imageName` | `sv-app` |
| `sv` | `splice-sv-node` | `ui.imageName` | `sv-web-ui` |
| `sv` | `splice-sv-node` | `persistence.initImageName` | `postgres:14` |
| `validator` | `splice-validator` | `imageName` | `validator-app` |
| `validator` | `splice-validator` | `wallet.imageName` | `wallet-web-ui` |
| `validator` | `splice-validator` | `ansWebUi.imageName` | `ans-web-ui` |
| `validator` | `splice-validator` | `persistence.initImageName` | `postgres:14` |

**Read this table off your own charts after a version bump** rather than
trusting it:

```
helm show values <STR_CHART_REPO>/splice-validator --version <version>
```

## Two things that behave differently

**`splice-postgres` renders `imageName` as the WHOLE reference**, tag included,
and its `imageRepo` does not reach it. That is why `STR_PG_IMAGE` exists and
carries the registry itself when `STR_IMAGE_REPO` is set.

**Every application chart runs a PostgreSQL init container**, and `imageRepo`
does not reach those either. The scripts already set `persistence.initImageName`
from `STR_PG_IMAGE`; `global-domain` has two of them, one per node.

## Digests - and what they mean for a mirror

**The charts render every Splice image reference with its digest**, and the
runtime refuses content that does not hash to it:

```
.../canton-participant:0.8.3@sha256:99e221fc...
```

At 0.8.3 ten of the twelve images are pinned this way. The two PostgreSQL
images, `postgres:14.24-trixie` and `postgres:14`, are not, and are pulled by
tag - which is why `download_images.sh` records `VERIFIED` or `UNPINNED` per
image rather than assuming.

**DO NOT READ `helm show values` FOR THIS.** Its `imageDigests` map is empty,
and that is not the instrument - the digests are rendered, not defaulted. The
instrument is `./shared/images.sh`, which prints what the manifests actually
carry.

**THE CONSEQUENCE FOR YOUR MIRROR, AND IT IS THE IMPORTANT ONE.** A digest
pins the bytes. An image COPIED into your registry - `skopeo copy`, `crane
copy`, `docker pull` and `push` of the same manifest - keeps its digest and
still matches. An image REBUILT, re-tagged in a way that rewrites the manifest,
or passed through a registry that re-compresses layers gets a new digest and
**the pull fails**, whatever `STR_IMAGE_REPO` or `STR_IMAGE_OVERRIDE` say. The
error will name a digest and not a name, so it reads as corruption rather than
as a mirroring choice.

So: mirror by copying manifests, not by rebuilding. `download_images.sh` pulls
by the pinned digest and writes `SHA256SUMS` over the saved tars, which is the
record to carry to an air-gapped machine.
