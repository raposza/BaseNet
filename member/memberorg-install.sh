#!/usr/bin/env bash
# Copyright (c) 2026 bentzn
# SPDX-License-Identifier: Apache-2.0
# Author Claude/bentzn
# Generated 2026-09-11T09:46:00Z
#
# Installs one MemberOrg's validator node - PostgreSQL, a participant, and a
# validator with the wallet and name-service UIs - into its own namespace, the
# way an organization deploys one against DevNet, TestNet or MainNet, pointed
# at the network in basenet.conf instead. Run memberorg-secrets.sh first.
#
#   ./memberorg-install.sh memberorg-a            install
#   ./memberorg-install.sh --render memberorg-a   manifests only, no cluster
#   ./memberorg-install.sh --overlay <dir> memberorg-a   with an overlay
#
# --overlay names a directory of per-release values files: <release>.yaml in
# it is passed AFTER that release's own values/ file, so it
# overrides any key without editing the tree. The BaseNet Validator's
# install.sh takes the same flag.
# THE TWO OVERLAYS ARE NOT INTERCHANGEABLE: a Member's releases are named
# postgres, participant and validator, and the BaseNet Validator has releases
# called participant and validator too, sized for entirely different pods.
# nmt_overlay.py --member writes to its own directory for that reason.
#
# The name is the namespace, the validator's party hint and its node
# identifier. A second MemberOrg is the same command with another name.
#
# ONBOARDING NEEDS NO SECRET. svSponsorAddress is set and no onboarding secret
# is, which the validator chart answers with its DevNet onboarding path. That
# works only against an SV founded with isDevNet: true, as values/sv.yaml is.
#
# The MemberOrg table is here and only here. render.sh renders the MemberOrg
# side by calling this script with --render, so the two cannot disagree.
set -euo pipefail
cd "$(dirname "$0")"
. ../basenet.conf
# basenet.conf.local, when present, overrides it and is never committed.
[ ! -f ../basenet.conf.local ] || . ../basenet.conf.local
. ./memberorg-common.sh

FLAG_RENDER=0
STR_OVERLAY=
while true; do
    case "${1:-}" in
        --render)  FLAG_RENDER=1; shift ;;
        --overlay) STR_OVERLAY="${2:-}"
                   [ -d "$STR_OVERLAY" ] || { echo "not a directory: ${2:-}" >&2; exit 2; }
                   shift 2 ;;
        --*)       echo "usage: ./memberorg-install.sh [--render] [--overlay <dir>] <name>" >&2
                   exit 2 ;;
        *)         break ;;
    esac
done
STR_MEMBERORG="${1:-}"
check_memberorg "$STR_MEMBERORG" || exit 2

if [ "$FLAG_RENDER" = "0" ]; then
    # Refuse the shipped placeholder rather than installing against it.
    case "$STR_OIDC_BASE_URL" in
        *example.internal*)
            echo "set STR_OIDC_BASE_URL in basenet.conf.local - it is still the placeholder"
            exit 1 ;;
    esac
    kubectl get ns "$STR_MEMBERORG" >/dev/null 2>&1 \
        || { echo "no namespace $STR_MEMBERORG - run ./memberorg-secrets.sh $STR_MEMBERORG first"; exit 1; }
fi

STR_JWKS="${STR_OIDC_JWKS_URL:-$STR_OIDC_BASE_URL/oauth2/jwks}"
# The audience the participant requires on the validator's tokens, and the
# person whose wallet the validator's own party is - both per-MemberOrg
# settings, see memberorg-common.sh.
STR_LEDGER_AUD=$(memberorg_setting STR_MO_LEDGER_AUDIENCE)
STR_WALLET_USER=$(memberorg_setting STR_MO_WALLET_USER)

# install.sh's image rewrite, for the same three charts it installs.
LST_REPO=()
LST_REPO_PG=()
LST_INIT=()
if [ -n "$STR_IMAGE_REPO" ]; then
    LST_REPO=(--set imageRepo="$STR_IMAGE_REPO")
    LST_REPO_PG=(--set imageName="$STR_IMAGE_REPO/$STR_PG_IMAGE")
    LST_INIT=(--set persistence.initImageName="$STR_IMAGE_REPO/$STR_PG_IMAGE")
fi

LST_WAIT=()
[ "$FLAG_RENDER" = "1" ] || LST_WAIT=(--wait --timeout 10m)


. ../shared/image-overrides.sh
check_image_override

function install() {
    local lstOver=()
    [ -z "$STR_OVERLAY" ] || [ ! -f "$STR_OVERLAY/$1.yaml" ] || lstOver=(-f "$STR_OVERLAY/$1.yaml")
    if [ "$FLAG_RENDER" = "1" ]; then
        helm template "$1" "$STR_CHART_REPO/$2" \
            --version "$STR_SPLICE_VERSION" -n "$STR_MEMBERORG" -f "values/$3" \
            "${lstOver[@]+"${lstOver[@]}"}" "${@:4}" $(lst_image_override "$1")
    else
        helm upgrade --install "$1" "$STR_CHART_REPO/$2" \
            --version "$STR_SPLICE_VERSION" -n "$STR_MEMBERORG" -f "values/$3" \
            "${lstOver[@]+"${lstOver[@]}"}" "${@:4}" $(lst_image_override "$1")
    fi
}


install postgres    splice-postgres    postgres.yaml "${LST_WAIT[@]}" \
    --set db.volumeStorageClass="$STR_STORAGE_CLASS" \
    "${LST_REPO_PG[@]}"
install participant splice-participant participant.yaml \
    --set auth.jwksUrl="$STR_JWKS" \
    --set auth.targetAudience="$STR_LEDGER_AUD" \
    "${LST_REPO[@]}" "${LST_INIT[@]}"
install validator   splice-validator   validator.yaml \
    --set nodeIdentifier="$STR_MEMBERORG" \
    --set validatorPartyHint="$STR_MEMBERORG" \
    --set scanAddress="$STR_EXT_SCAN_URL" \
    --set svSponsorAddress="$STR_EXT_SPONSOR_URL" \
    --set synchronizer.url="$STR_EXT_SEQUENCER_URL" \
    --set auth.jwksUrl="$STR_JWKS" \
    --set auth.audience="$STR_OIDC_AUDIENCE" \
    --set validatorWalletUser="$STR_WALLET_USER" \
    --set spliceInstanceNames.networkName="$STR_NETWORK_NAME" \
    --set pvc.volumeStorageClass="$STR_STORAGE_CLASS" \
    "${LST_REPO[@]}" "${LST_INIT[@]}"

[ "$FLAG_RENDER" = "1" ] && exit 0
echo
echo "=== MemberOrg $STR_MEMBERORG installed. It is onboarded when validator-app turns Ready."
echo "    kubectl -n $STR_MEMBERORG get pods -w"
