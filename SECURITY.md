<!-- Copyright (c) 2026 bentzn -->
<!-- SPDX-License-Identifier: Apache-2.0 -->
# Security

## Reporting a vulnerability

Write to **info@raposza.com**. Say what you found, how to reproduce it, and
what you think it lets an attacker do. You will get an acknowledgement; if the
finding is valid you will be told what is being done about it and when the fix
lands.

Please do not open a public issue for a vulnerability before it is fixed.

There is no bug bounty.

## What is in scope

This repository: the shell scripts, the Helm values files, the Python tools and
the `jwtmint` module.

**Not in scope: the Splice Helm charts and the container images.** They are
Digital Asset's, they are pulled from their registry at install time, and
nothing from them is redistributed here. Report anything you find in them to
their maintainers.

## Supported versions

One release of this repository targets one Splice release, named by
`STR_SPLICE_VERSION` in `basenet.conf`. Only the current tip of the default
branch is supported; there are no maintenance branches and no backports.

## The short statement of the model

**BaseNet is a disposable test network and it is not hardened.** It exists so
that a Splice release can be exercised before it reaches a network anyone
cares about. Three consequences you should read before running it:

* **The bundled identity provider, `shared/jwtmint/`, has no authentication of
  its own.** It listens on every interface, it will mint a token for any
  subject and any audience on request, and it serves its own PRIVATE keys at
  `/oauth2/jwks-private`. Anyone who can reach its port can issue a token this
  network accepts. That is deliberate for a test provider, and it is why it
  belongs on a development machine on a network you control. It is replaceable
  by settings: any OIDC provider that passes `shared/oidc_check.py` will serve.
* **The network-side nodes run with authentication disabled** -
  `disableAuth` and `fixedTokens: true` in `validator/values/`. The
  ledger-API token those nodes use is signed with a secret the vendor's chart
  hardcodes, and it never expires.
* **The passwords in `basenet.conf` are declared throwaway** and are in plain
  text on purpose. Nothing here should hold a credential you would mind
  publishing.

`docs/security-review.md` is the full posture document: what executes, what is
downloaded and how its integrity is established, what listens and with what
authentication, what is written to disk, what key material is held, what leaves
the machine, and the limitations that follow.
