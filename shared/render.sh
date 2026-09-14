#!/usr/bin/env bash
# Copyright (c) 2026 bentzn
# SPDX-License-Identifier: Apache-2.0
# Author Claude/bentzn
# Generated 2026-09-07T00:00:00Z
#
# THE GATE. Renders all nine releases, and a MemberOrg's three through
# memberorg-install.sh --render, with no cluster contact. Every chart
# ships its own values.schema.json and Helm enforces it, so a values file that
# would fail at install time fails here instead, in seconds, against nothing.
#
# Run this after every edit to a values file and after every version bump.
set -euo pipefail
cd "$(dirname "$0")"
. ../basenet.conf
# basenet.conf.local, when present, overrides it and is never committed.
[ ! -f ../basenet.conf.local ] || . ../basenet.conf.local

STR_OUT=$(mktemp -d)
. ./image-overrides.sh
check_image_override

STR_JWKS="${STR_OIDC_JWKS_URL:-$STR_OIDC_BASE_URL/oauth2/jwks}"

function render() {
    echo "===== $1"
    helm template "$2" "$STR_CHART_REPO/$1" --version "$STR_SPLICE_VERSION" \
        -n "$STR_NAMESPACE" -f "../validator/values/$3" "${@:4}" \
        $(lst_image_override "$2") > "$STR_OUT/$2.yaml"
    echo "    ok  $(grep -c '^kind:' "$STR_OUT/$2.yaml") resources"
}

render splice-postgres      sequencer-pg    postgres-sequencer.yaml
render splice-postgres      mediator-pg     postgres-mediator.yaml
render splice-postgres      participant-pg  postgres-participant.yaml
render splice-postgres      apps-pg         postgres-apps.yaml
render splice-global-domain global-domain-0 global-domain.yaml \
    --set sequencer.driver.password="$STR_PG_PASSWORD"
render splice-participant   participant     participant.yaml
render splice-scan          scan            scan.yaml
render splice-sv-node       sv              sv.yaml --set auth.jwksUrl="$STR_JWKS" \
    --set auth.audience="$STR_OIDC_AUDIENCE"
render splice-validator     validator       validator.yaml --set auth.jwksUrl="$STR_JWKS" \
    --set auth.audience="$STR_OIDC_AUDIENCE"

echo "===== the MemberOrg side, as memberorg-install.sh would install it"
../member/memberorg-install.sh --render memberorg-render > "$STR_OUT/memberorg.yaml"
echo "    ok  $(grep -c '^kind:' "$STR_OUT/memberorg.yaml") resources"

echo
if grep -hq "standard-rwo" "$STR_OUT"/*.yaml; then
    echo "!!! a chart default storage class leaked - set pvc.volumeStorageClass"
    rm -rf "$STR_OUT"
    exit 1
fi
echo "=== ALL RENDERED - nothing was installed"
rm -rf "$STR_OUT"
