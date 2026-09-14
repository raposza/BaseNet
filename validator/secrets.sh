#!/usr/bin/env bash
# Copyright (c) 2026 bentzn
# SPDX-License-Identifier: Apache-2.0
# Author Claude/bentzn
# Generated 2026-09-07T00:00:00Z
#
# Creates the namespace and every secret the charts mount with optional:false.
# Run this before install.sh.
#
# The ledger-api token is HS256 signed with the 6-byte key "unsafe", because
# splice-participant's disableAuth is not auth-off: it configures
# unsafe-jwt-hmac-256 with that secret hardcoded in the chart template. Any
# minter enforcing the 32-byte HMAC floor of RFC 7518 cannot produce this
# token, so it is signed here. It carries no exp claim and never expires.
#
# This is a disposable environment. Nothing here is a credential worth
# protecting and none of it should exist on a network you care about.
set -euo pipefail
cd "$(dirname "$0")"
. ../basenet.conf
# basenet.conf.local, when present, overrides it and is never committed.
[ ! -f ../basenet.conf.local ] || . ../basenet.conf.local

# Refuse the shipped placeholder rather than founding a network against it.
case "$STR_OIDC_BASE_URL" in
    *example.internal*)
        echo "set STR_OIDC_BASE_URL in basenet.conf.local - it is still the placeholder"
        exit 1 ;;
esac

STR_AUD=https://ledger_api.example.com
STR_USER=ledger-api-user

STR_TOKEN=$(python3 - <<PY
import base64, hmac, hashlib, json
def enc(arrBytes): return base64.urlsafe_b64encode(arrBytes).rstrip(b"=")
strHead = enc(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
strBody = enc(json.dumps({"sub": "$STR_USER", "aud": "$STR_AUD"}, separators=(",", ":")).encode())
strSig = enc(hmac.new(b"unsafe", strHead + b"." + strBody, hashlib.sha256).digest())
print((strHead + b"." + strBody + b"." + strSig).decode())
PY
)
test -n "$STR_TOKEN" || { echo "NO TOKEN - refusing to write an empty secret"; exit 1; }

kubectl create ns "$STR_NAMESPACE" --dry-run=client -o yaml | kubectl apply -f -

kubectl -n "$STR_NAMESPACE" create secret generic postgres-secrets \
    --from-literal=postgresPassword="$STR_PG_PASSWORD" \
    --dry-run=client -o yaml | kubectl apply -f -

# scan-app reads the SV's secret, not one of its own: its auth env include
# passes keyName "sv".
for STR_APP in sv validator; do
    kubectl -n "$STR_NAMESPACE" create secret generic "splice-app-${STR_APP}-ledger-api-auth" \
        --from-literal=ledger-api-user="$STR_USER" \
        --from-literal=audience="$STR_AUD" \
        --from-literal=token="$STR_TOKEN" \
        --dry-run=client -o yaml | kubectl apply -f -
done

# sv-web-ui mounts this unconditionally - it does not honour disableAuth.
kubectl -n "$STR_NAMESPACE" create secret generic splice-app-sv-ui-auth \
    --from-literal=url="$STR_OIDC_BASE_URL" \
    --from-literal=client-id="$STR_OIDC_SV_UI_CLIENT_ID" \
    --dry-run=client -o yaml | kubectl apply -f -

kubectl -n "$STR_NAMESPACE" get secret
