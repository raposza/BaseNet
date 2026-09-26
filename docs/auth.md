<!-- Copyright (c) 2026 bentzn -->
<!-- SPDX-License-Identifier: Apache-2.0 -->
# The auth model, per chart

Reviewed 2026-09-26 for v0.3.0.

## The rule this deployment follows

**BaseNet is for testing and will never be secure. The bundled identity
provider will never be secure as BaseNet runs it** - it mints a token for
anyone who asks and serves its own private keys, deliberately.

**Everything else follows the principles of a production deployment exactly.**
Every component is a registered client, obtains its token the normal way,
presents it, has it verified against the JWKS, and has it expire. The point is
that the code paths, the settings and the failures are the real ones, found
here rather than in production. Pointing the stack at a different OIDC provider
is a change of settings and nothing else.

Those are two independent things and they are never traded against each other:
the provider enforces nothing, and the system uses it properly. **Any reasoning
from the provider's weakness to fewer clients, less verification or longer
lifetimes is wrong by definition.**

Where this deployment does not yet meet that rule, it is marked below as a
DEVIATION. A deviation is a defect with a reason, not a design choice.

## The charts

Splice's charts do not share one authentication switch, and the names are
misleading in two places. This is what each one actually does in this
deployment.

### `disableAuth` is not authentication off - A DEVIATION on the network side

`splice-participant` accepts `disableAuth: true` and its own values file calls
it highly insecure, which is accurate but not specific. What the template
configures is `unsafe-jwt-hmac-256` with the secret `unsafe` hardcoded into the
chart. The participant still requires a token and still verifies its signature;
it simply verifies it against a six-byte key that everyone has.

Two consequences:

* An RS256 token is rejected on algorithm, not on signature. A real OIDC
  provider cannot serve this participant while `disableAuth` is on.
* Any minter enforcing the 32-byte HMAC floor of RFC 7518 refuses to sign with
  a six-byte key. `secrets.sh` therefore signs the ledger-api token inline, in
  python, rather than asking a provider for it. The token carries no `exp`
  claim and never expires, which is appropriate for an environment whose whole
  purpose is to be thrown away.

### `cluster.fixedTokens` - A DEVIATION on the network side

**This is the shortcut, and it is the one thing here that does not follow the
rule above.** A BaseNet Member does it properly - `disableAuth: false`,
`fixedTokens: false`, RS256 against the JWKS, `client_credentials` - and the
network side does not. Because the code path is absent rather than permissive,
swapping the identity provider changes nothing about it, which defeats the
purpose of the exercise on that half of the stack.

With `fixedTokens: true` the SV, scan and validator apps take a static token
from a mounted secret instead of running a `client_credentials` grant against
a token endpoint. No token endpoint has to exist, and nothing has to be
registered anywhere.

### The bundled provider

Raposza OIDC is that provider, and `shared/oidc.sh` runs it: the newest
`raposza-oidc-server-*-app.jar` in your local Maven repository, a separate
project that this repository does not build. It is an OpenID Provider:
discovery, a JWKS endpoint, a login page for the users in
`STR_OIDC_USERS`, the authorization code flow with PKCE that browser
applications use, UserInfo and logout. It keeps its keys, users and clients
in `STR_OIDC_DIR`, its own directory, and mints any token on request through
`/mint`. **No client is registered there**, so `client_credentials` checks no
client id and no secret; registering the first client would turn the checks on
for every client, and the Members' machine clients and the UIs' redirect URIs
would then all have to be registered. It publishes its own private keys, which
is a feature in a throwaway environment and a reason it belongs nowhere else.

It cannot sign the participant's ledger-api token: it enforces the 32-byte HMAC
floor and that token needs a six-byte key. `secrets.sh` signs that one inline.

### The OIDC provider is still needed, for the UIs

`sv-web-ui` mounts `splice-app-sv-ui-auth` unconditionally - it does not honour
`disableAuth` - and the SV and validator apps validate browser-issued tokens
against `auth.jwksUrl`. Both come from `basenet.conf`.

That address is resolved **from inside the cluster**. `localhost` inside a pod
is that pod; use a host address the cluster can route to.

## Using your own OIDC provider instead of the bundled one

The rule at the top of this file is the whole of it: the bundled provider
stands in for a production identity provider, same protocol and same concepts,
and moving to another one is a change of settings and nothing more.

`oidc_check.py` is the contract both are held to. Run it against your
provider before you switch, with a user and a public client registered
there:

```
OIDC_USERNAME=<user> OIDC_PASSWORD=<password> OIDC_CLIENT_ID=<client> \
OIDC_REDIRECT_URI=<a redirect URI registered for that client> \
OIDC_AUDIENCE=<STR_OIDC_AUDIENCE> python3 oidc_check.py <issuer>
```

Every result names the clause it tests. A FAIL is something the web UIs or
the backends will trip over; `OIDC_ALLOW_HTTP=1` downgrades plain http to a
warning for a provider on a test network.

Nothing in the charts is specific to the bundled provider. They take exactly three things
from an identity provider, and each is a key in `basenet.conf`:

| key | what the charts do with it |
| --- | --- |
| `STR_OIDC_JWKS_URL` | `auth.jwksUrl` on the sv and validator apps: where they fetch the keys that verify inbound tokens. Take it from your provider's `/.well-known/openid-configuration`, field `jwks_uri` |
| `STR_OIDC_AUDIENCE` | `auth.audience` on the same two apps: the `aud` a token must carry. Configure your provider to issue it |
| `STR_OIDC_BASE_URL`, `STR_OIDC_SV_UI_CLIENT_ID` | the `url` and `client-id` in `splice-app-sv-ui-auth`, which the SV web UI logs in with. Register that client at your provider |

**THE ROW ABOVE IS THE DEVIATION SPEAKING.** Your provider needs no client for
the apps themselves only because of it: with
`cluster.fixedTokens` they present static tokens from their secrets, and the
participant's ledger-api token is signed by `secrets.sh` whichever provider
you use. Skip `oidc.sh` and leave Java out.

The keys it serves must be reachable from inside the cluster, like the
bundled provider's.

## Where the secrets come from

`secrets.sh` creates the namespace and four secrets:

| secret | mounted by |
| --- | --- |
| `postgres-secrets` | all four PostgreSQL releases and every app that persists |
| `splice-app-sv-ledger-api-auth` | sv-app, and scan-app, which reads the SV's secret rather than one of its own |
| `splice-app-validator-ledger-api-auth` | validator-app |
| `splice-app-sv-ui-auth` | sv-web-ui |

The sequencer's database password is the exception: the global-domain chart
renders it inline into the pod spec rather than reading a `secretKeyRef`, so
`install.sh` passes it with `--set` from `basenet.conf`.

## A BaseNet Member authenticates the production way

A BaseNet Member is what a MemberOrg runs against DevNet, TestNet or MainNet,
so it authenticates the way that organization will in production - against
the identity provider in `basenet.conf`, with nothing signed by a shared
secret:

| who | how | settings |
| --- | --- | --- |
| the participant's ledger API | verifies RS256 tokens against the provider's JWKS, requires an audience | `STR_OIDC_JWKS_URL`, `STR_MO_LEDGER_AUDIENCE` |
| the validator, towards its participant | `client_credentials` at the provider, found through its discovery document | `STR_OIDC_WELL_KNOWN_URL`, `STR_MO_CLIENT_ID`, `STR_MO_CLIENT_SECRET`, `STR_MO_LEDGER_USER` |
| a person, in the wallet and name-service UIs | the authorization code flow with PKCE, signing in at the provider | `STR_OIDC_BASE_URL`, `STR_MO_WALLET_UI_CLIENT_ID`, `STR_MO_ANS_UI_CLIENT_ID` |
| the validator's API, towards those UIs | verifies the person's RS256 token against the JWKS and its audience | `STR_OIDC_JWKS_URL`, `STR_OIDC_AUDIENCE` |
| the wallet's owner | the validator's own party belongs to this user | `STR_MO_WALLET_USER` |

Every BaseNet Member has its own wallet user - `%s-user` by default, `%s` being
the MemberOrg's name - and the bundled provider's users, `STR_OIDC_USERS`,
list one for `memberorg-a` and one for `memberorg-b`. A MemberOrg with another
name needs its user added there, and the provider restarted, before it can
sign in.

To move a BaseNet Member to Keycloak: register the machine client, the two UI
clients and the wallet user there; set the `STR_OIDC_*` keys to the realm and
the `STR_MO_*` keys to what Keycloak issued - its `sub` values are ids, not
names, so `STR_MO_LEDGER_USER` and `STR_MO_WALLET_USER` take those; run
`oidc_check.py` against it; then `memberorg-secrets.sh` and
`memberorg-install.sh` as before. A participant's authentication cannot change
under it, so an installed BaseNet Member is torn down and installed again.

the BaseNet Validator's own nodes keep `disableAuth` and fixed tokens: they stand in for
the network's super validator, which in production is somebody else's.
