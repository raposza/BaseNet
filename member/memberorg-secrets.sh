#!/usr/bin/env bash
# Copyright (c) 2026 bentzn
# SPDX-License-Identifier: Apache-2.0
# Author Claude/bentzn
#
# Creates one MemberOrg's namespace and the secrets its charts mount with
# optional:false. Run this before memberorg-install.sh, with the same name.
#
#   ./memberorg-secrets.sh memberorg-a
#
# A MemberOrg authenticates the way an organization does against DevNet,
# TestNet or MainNet, and every value here is a setting in basenet.conf -
# see docs/auth.md. Pointing a MemberOrg at Keycloak changes the settings and
# nothing in this script:
#
#   splice-app-validator-ledger-api-auth   the validator's machine client: the
#                                          discovery URL it finds the token
#                                          endpoint at, its client id and
#                                          secret, the audience it asks for,
#                                          and the ledger user its tokens name
#   splice-app-wallet-ui-auth              the provider and client the wallet
#   splice-app-cns-ui-auth                 and name-service UIs sign people in
#                                          with
#
# The participant takes its admin user from the validator's secret, so the
# user the validator authenticates as is the participant's administrator.
#
# This is a test environment: the client secret sits in the settings, and the
# bundled provider does not check it. The concepts are production's.
set -euo pipefail
cd "$(dirname "$0")"
. ../basenet.conf
# basenet.conf.local, when present, overrides it and is never committed.
[ ! -f ../basenet.conf.local ] || . ../basenet.conf.local
. ./memberorg-common.sh

STR_MEMBERORG="${1:-}"
check_memberorg "$STR_MEMBERORG" || exit 2

case "$STR_OIDC_BASE_URL" in
    *example.internal*)
        echo "set STR_OIDC_BASE_URL in basenet.conf.local - it is still the placeholder"
        exit 1 ;;
esac

STR_WELL_KNOWN="${STR_OIDC_WELL_KNOWN_URL:-$STR_OIDC_BASE_URL/.well-known/openid-configuration}"
STR_CLIENT_ID=$(memberorg_setting STR_MO_CLIENT_ID)
STR_CLIENT_SECRET=$(memberorg_setting STR_MO_CLIENT_SECRET)
STR_LEDGER_USER=$(memberorg_setting STR_MO_LEDGER_USER)
STR_LEDGER_AUD=$(memberorg_setting STR_MO_LEDGER_AUDIENCE)
STR_WALLET_USER=$(memberorg_setting STR_MO_WALLET_USER)
STR_WALLET_CLIENT=$(memberorg_setting STR_MO_WALLET_UI_CLIENT_ID)
STR_ANS_CLIENT=$(memberorg_setting STR_MO_ANS_UI_CLIENT_ID)

for STR_KEY in STR_CLIENT_ID STR_CLIENT_SECRET STR_LEDGER_USER STR_LEDGER_AUD \
        STR_WALLET_USER STR_WALLET_CLIENT STR_ANS_CLIENT; do
    [ -n "${!STR_KEY}" ] || { echo "$STR_KEY is empty - check the STR_MO_* settings" >&2; exit 1; }
done

kubectl create ns "$STR_MEMBERORG" --dry-run=client -o yaml | kubectl apply -f -
kubectl label ns "$STR_MEMBERORG" "$STR_MEMBERORG_LABEL=true" --overwrite

kubectl -n "$STR_MEMBERORG" create secret generic postgres-secrets \
    --from-literal=postgresPassword="$STR_PG_PASSWORD" \
    --dry-run=client -o yaml | kubectl apply -f -

kubectl -n "$STR_MEMBERORG" create secret generic splice-app-validator-ledger-api-auth \
    --from-literal=url="$STR_WELL_KNOWN" \
    --from-literal=client-id="$STR_CLIENT_ID" \
    --from-literal=client-secret="$STR_CLIENT_SECRET" \
    --from-literal=audience="$STR_LEDGER_AUD" \
    --from-literal=ledger-api-user="$STR_LEDGER_USER" \
    --dry-run=client -o yaml | kubectl apply -f -

kubectl -n "$STR_MEMBERORG" create secret generic splice-app-wallet-ui-auth \
    --from-literal=url="$STR_OIDC_BASE_URL" \
    --from-literal=client-id="$STR_WALLET_CLIENT" \
    --dry-run=client -o yaml | kubectl apply -f -

kubectl -n "$STR_MEMBERORG" create secret generic splice-app-cns-ui-auth \
    --from-literal=url="$STR_OIDC_BASE_URL" \
    --from-literal=client-id="$STR_ANS_CLIENT" \
    --dry-run=client -o yaml | kubectl apply -f -


# THE KMS DRIVER, when FLAG_MO_KMS is on. The jar rides in a secret and is
# mounted read-only at /mockkms; nothing is pushed to a registry and no image
# is built. A real driver goes in the same way - replace the jar and the name.
if [ "${FLAG_MO_KMS:-0}" = "1" ]; then
    STR_JAR="../$STR_MO_KMS_JAR"
    [ -f "$STR_JAR" ] || { echo "no driver jar at $STR_MO_KMS_JAR - run shared/mockkms/build.sh first" >&2; exit 1; }
    kubectl -n "$STR_MEMBERORG" create secret generic mockkms-driver \
        --from-file=driver.jar="$STR_JAR" \
        --dry-run=client -o yaml | kubectl apply -f -
fi

kubectl -n "$STR_MEMBERORG" get secret

echo
echo "=== $STR_MEMBERORG signs in as:"
echo "    machine  client $STR_CLIENT_ID, ledger user $STR_LEDGER_USER, audience $STR_LEDGER_AUD"
echo "    wallet   user $STR_WALLET_USER, clients $STR_WALLET_CLIENT and $STR_ANS_CLIENT"
# THE BUNDLED PROVIDER'S USERS are a setting too; a wallet user it does not
# know cannot sign in. Another provider keeps its own and this does not apply.
case ",${STR_OIDC_USERS:-}," in
    *",$STR_WALLET_USER:"*) ;;
    *) echo "    NOTE: $STR_WALLET_USER is not in STR_OIDC_USERS - the bundled provider will refuse its sign-in" ;;
esac
