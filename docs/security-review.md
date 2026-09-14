<!-- Copyright (c) 2026 bentzn -->
<!-- SPDX-License-Identifier: Apache-2.0 -->
# Security posture

**This is a REVIEW, not an audit.** It was written by the people who wrote the
code, from a static reading of this tree on 2026-09-13. Nothing in it was
established by a third party, and no third party has signed anything about it.
When an external audit exists it will be published beside this document,
unedited.

It is organised as the questions a reviewer asks, in the order they get asked.
Read it with `SECURITY.md`, which carries the short statement of the model and
the reporting channel.

**What BaseNet is, in one paragraph.** A set of Helm values files, shell
scripts and Python tools that stand up a complete, private Canton Network on a
Kubernetes cluster you can throw away - the BaseNet Validator - and one or more
BaseNet Members pointed at it, and destroy them again. It exists to exercise a
Splice release before it reaches a network that matters. It is not a production
deployment, it is not hardened, and the sections below say exactly where that
shows.

## 1 - What executes, and how the command line is built

`helm`, `kubectl` and `python3` on your machine, `java` and `mvn` if you build
the bundled identity provider, `docker` only for `shared/download_images.sh`,
and `dpm` or `daml` only if you build the pet shop fixture for the load test.

**Nothing in this repository downloads a script and runs it.** There is no
`curl | sh`, no installer, and no self-update. Every external program invoked
is one you already have on your PATH.

Command lines are built in shell arrays from the settings in `basenet.conf`
and passed to `helm` and `kubectl` as separate arguments rather than through a
shell string. `basenet.conf` and `basenet.conf.local` are therefore TRUSTED
INPUT: they are `source`d by every script, so anything in them executes with
your privileges. They are your own files and they are the only configuration
surface; do not source someone else's.

## 2 - What is downloaded, from where, and how integrity is established

**Helm charts** come from `oci://ghcr.io/digital-asset/decentralized-canton-sync/helm`
over TLS, at install time, at the version in `basenet.conf`.
`STR_CHART_REPO` points that at your own registry instead.

**Container images** are pulled by the cluster, not by these scripts. The
charts render every Splice image reference WITH ITS DIGEST - measured, ten of
ten - so the runtime refuses content that does not hash to what the chart
names. `postgres:14` is the exception and is pulled by tag.
`shared/images.sh` derives the full list from
the rendered manifests rather than from a hand-kept list, and
`STR_IMAGE_REPO` rewrites every reference to your mirror.

**`shared/download_images.sh`** pulls each image by its pinned digest,
confirms the pulled image carries that digest, records `VERIFIED` or
`UNPINNED` per image in `digests.txt`, and writes `SHA256SUMS` over the saved
tars so the folder can be checked on another machine with `sha256sum -c`.

**Maven dependencies** for `jwtmint` come from Maven Central under Maven's own
checksum handling.

**Nothing from the vendor is redistributed in this repository.** No chart, no
image, no binary.

**What is NOT established:** no signature or build-provenance verification on
charts or images. TLS to the registry and the charts' digest pins are the
integrity controls. If your organization requires signature verification, do it
in your mirror.

## 3 - What listens, on which interface, with what authentication

**`shared/jwtmint.sh` - the bundled identity provider. This is the one that
matters.** It listens on the port in `STR_OIDC_BASE_URL`, 32002 by default,
and it binds EVERY interface. That is deliberate: the cluster has to reach its
JWKS, and a loopback bind fails in a way that looks like a key problem.

Its posture, stated without euphemism:

* No endpoint requires authentication.
* Its `client_credentials` grant accepts any client id and any client secret,
  and mints a token with whatever subject, audience, scope and lifetime the
  request asks for. The secret is not checked.
* `GET /oauth2/jwks-private` and `GET /jwks-private.json` return the full key
  set INCLUDING the private key material.

So anyone who can reach that port can mint a token this BaseNet accepts, and
can take the signing keys and mint their own elsewhere. Run it on a machine and
a network where that is acceptable. Its people-facing grants - the login page,
`authorization_code` with PKCE, `refresh_token` - ARE checked as the
specifications require; the machine grant is the unchecked one.

**It is replaceable by settings alone.** `STR_OIDC_JWKS_URL`,
`STR_OIDC_AUDIENCE`, `STR_OIDC_SV_UI_CLIENT_ID` and the `STR_MO_*` keys point
the same deployment at any OIDC provider. `shared/oidc_check.py` is the
contract both are held to: it tests discovery, the JWKS, PKCE, the code and
refresh grants, CORS, UserInfo and RP-initiated logout against the
specifications.

**`shared/ui.py`** binds 127.0.0.1 only, one port per namespace from
`STR_UI_PORT`. It is a stand-in for the ingress a real deployment would have:
it serves the vendor's web UI pages and proxies their API calls, reaching the
backends through `kubectl port-forward`, so it acts with your kubeconfig's
rights. It authenticates nobody itself; the pages sign in at the identity
provider.

**`member/loadtest.py`** opens a `kubectl port-forward` on 127.0.0.1 for the
duration of a run.

**Inside the cluster:** the BaseNet Validator's nodes run with `disableAuth`
and `fixedTokens: true`. A BaseNet Member's participant verifies RS256 tokens
against the provider's JWKS, and its validator authenticates to the provider
with `client_credentials` - the production shape. **This repository deploys no
ingress**, so nothing it installs is reachable from outside the cluster unless
you add one.

## 4 - What is written to disk, and with what permissions

On your machine:

| where | what |
| --- | --- |
| `~/.raposza/jwtmint/keys` | the identity provider's JWKS, public AND private, persisted so a restart keeps the same keys |
| `~/.raposza/fixtures/petshop/` | the built fixture DAR and its package id, for the load test |
| `/tmp/bn-overlay-*` | the memory overlays `run-optimized.sh` writes |
| `/tmp/bn-load-*.csv`, `/tmp/bn-watch-*.csv` | the load test's latency rows and the memory samples |
| `basenet.conf.local` | your own settings, git-ignored and excluded from any export |
| `shared/jwtmint/target/` | Maven build output |

Files are written with the process umask and no explicit mode. **The private
key file is not chmod 0600** - on a default umask it is readable by your group
and by others. If that matters on your machine, tighten
`~/.raposza/jwtmint/keys` yourself.

In the cluster: PersistentVolumeClaims on `STR_STORAGE_CLASS` for the
PostgreSQL instances, and Kubernetes Secrets in each namespace.

**Nothing needs root and nothing is written outside your home directory,
`/tmp` and the cluster.** No script uses `sudo`; nothing touches `/etc`,
`/usr/local` or `/var`.

## 5 - What credential or key material is held, where, and for how long

* **The identity provider's signing keys** - twelve of them, one per algorithm
  - live on disk under `~/.raposza/jwtmint/keys` indefinitely, and are served
  to anyone who asks. Delete the directory to rotate; the next start generates
  a new set.
* **`basenet.conf`** holds `STR_PG_PASSWORD` (`supersafe` as shipped),
  `STR_JWTMINT_USERS` as `name:password` pairs in plain text, and
  `STR_MO_CLIENT_SECRET` as a template. All are declared throwaway. The
  bundled provider does not check client secrets at all.
* **`validator/secrets.sh` mints an HS256 token signed with the six-byte key
  `unsafe`.** That is not a choice this repository made: `splice-participant`'s
  `disableAuth` is not auth-off - it configures `unsafe-jwt-hmac-256` with that
  secret hardcoded in the chart template, and no minter that enforces RFC
  7518's 32-byte HMAC floor can produce the token. **It carries no `exp` claim
  and never expires.** It is stored in a Kubernetes Secret in the namespace.
* **The PostgreSQL password** reaches the `global-domain` release as a Helm
  `--set` argument and ends up in the pod spec.
* **Tokens the load test uses** are held in memory for the length of a run.

## 6 - What is logged, and whether a secret can reach a log line

The scripts print names, not values: `jwtmint.sh` strips the passwords out of
`STR_JWTMINT_USERS` before echoing the user names, `memberorg-secrets.sh`
prints the client id, the ledger user and the audience but not the secret, and
`validator/secrets.sh` prints `kubectl get secret`, which lists names only. The
identity provider logs at INFO and logs key IDs, never key material.

**Where values do land:** Helm stores every release's resolved values in a
Secret in the cluster, so `helm get values` and `helm history` will show what
was passed with `--set`, including the sequencer's PostgreSQL password. Anyone
with read access to those namespaces can read it.

## 7 - What leaves the machine

Requests to the chart registry and the image registry - the vendor's, or your
mirror if `STR_CHART_REPO` and `STR_IMAGE_REPO` are set. Maven Central while
`jwtmint` builds. Whatever your kubeconfig points at. `shared/oidc_check.py`
contacts only the issuer you name on its command line.

**There is no telemetry, no analytics, no crash reporting and no call home.**
Nothing in this repository sends anything anywhere else, ever.

## 8 - What privileges are needed

No root on your machine. In Kubernetes, enough to create and delete namespaces
and to install charts in them - namespace creation is cluster-scoped, so a
namespace-scoped role is not sufficient. The teardown scripts are the ones to
look at twice: `validator/teardown.sh` deletes the namespace named in
`basenet.conf`, and `member/memberorg-teardown.sh` refuses to delete a
namespace unless it carries the `basenet-member=true` label its own secrets
script set - so a mistyped name is refused rather than acted on.

## 9 - What third-party code is present

Source only, resolved at build time, nothing vendored and no binaries in the
repository:

* Nimbus JOSE+JWT - the JWT and JWK implementation behind `jwtmint`
* Spring Boot - its HTTP surface
* springdoc-openapi - its API documentation page

Everything else in the tree was written for it. The Splice charts and images
are the vendor's and are pulled, never redistributed - see `NOTICE`.

## 10 - How a release is built, and how a consumer verifies it

**There is no binary release.** You clone the source, and `jwtmint` is built by
Maven on your machine from the sources you can read. Nothing is downloaded
pre-built from us.

**What that leaves unverifiable:** there is no signed tag, no signed release
artefact and no build provenance for this repository, so a consumer's assurance
that what they cloned is what was published is Git and the remote's own
transport. Named here rather than left for a reviewer to notice.

## 11 - Known limitations

Each of these is a deliberate consequence of what BaseNet is for. They are
listed so nobody has to find them by reading.

| # | limitation |
| --- | --- |
| L-1 | The bundled identity provider is unauthenticated, binds every interface, mints a token for any subject on request, and serves its own private keys |
| L-2 | The network-side nodes run with `disableAuth` and `fixedTokens: true` |
| L-3 | The ledger-API token is signed with a secret the vendor's chart hardcodes and carries no expiry |
| L-4 | No signature or build-provenance verification on charts or images. The ten Splice images ARE digest-pinned by the charts; `postgres:14` is pulled by tag - `docs/images.md` |
| L-5 | The identity provider's private key file takes the process umask; it is not 0600 |
| L-6 | Passwords in `basenet.conf` are plain text and declared throwaway |
| L-7 | No release artefact and no tag is signed |
| L-8 | No ingress and no TLS between your machine and the cluster's services - `ui.py` proxies over plain HTTP on loopback |

**Do not run BaseNet on a network you do not control, and do not point it at
anything holding real value.**
