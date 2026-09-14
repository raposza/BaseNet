#!/usr/bin/env bash
# Copyright (c) 2026 bentzn
# SPDX-License-Identifier: Apache-2.0
# Author Claude/bentzn
#
# Installs the nine Helm releases, in order. Run secrets.sh first.
#
#   ./install.sh [--overlay <dir>]
#
# --overlay names a directory of per-release values files: <release>.yaml in
# it is passed AFTER that release's own values/ file, so it overrides any key
# without editing the tree. memsweep.py writes one per run. Absent, or absent
# for a release, nothing changes.
#
# --wait ONLY on the four PostgreSQL releases. The Canton nodes are
# identity.type=manual and stay uninitialised until sv-app founds the DSO, so
# they never report ready first and waiting on them times out every time.
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

STR_OVERLAY=
case "${1:-}" in
    "")        ;;
    --overlay) STR_OVERLAY="${2:-}"
               [ -d "$STR_OVERLAY" ] || { echo "not a directory: ${2:-}" >&2; exit 2; } ;;
    *)         echo "usage: ./install.sh [--overlay <dir>]" >&2; exit 2 ;;
esac

. ../shared/image-overrides.sh
check_image_override

STR_JWKS="${STR_OIDC_JWKS_URL:-$STR_OIDC_BASE_URL/oauth2/jwks}"

# Every Splice chart takes imageRepo as ONE top-level key, so a mirror is one
# argument per release. splice-postgres is the exception - see the conf.
LST_REPO=()
LST_REPO_PG=()
LST_INIT=()
LST_INIT_GD=()
if [ -n "$STR_IMAGE_REPO" ]; then
    LST_REPO=(--set imageRepo="$STR_IMAGE_REPO")
    LST_REPO_PG=(--set imageName="$STR_IMAGE_REPO/$STR_PG_IMAGE")
    # EVERY app chart runs a PostgreSQL init container and imageRepo reaches
    # none of them. global-domain has two, one per node.
    LST_INIT=(--set persistence.initImageName="$STR_IMAGE_REPO/$STR_PG_IMAGE")
    LST_INIT_GD=(--set sequencer.persistence.initImageName="$STR_IMAGE_REPO/$STR_PG_IMAGE"
                 --set mediator.persistence.initImageName="$STR_IMAGE_REPO/$STR_PG_IMAGE")
fi

function install() {
    local lstOver=()
    [ -z "$STR_OVERLAY" ] || [ ! -f "$STR_OVERLAY/$1.yaml" ] || lstOver=(-f "$STR_OVERLAY/$1.yaml")
    helm upgrade --install "$1" "$STR_CHART_REPO/$2" \
        --version "$STR_SPLICE_VERSION" -n "$STR_NAMESPACE" -f "values/$3" \
        "${lstOver[@]+"${lstOver[@]}"}" "${@:4}" $(lst_image_override "$1")
}

install sequencer-pg    splice-postgres      postgres-sequencer.yaml   --wait --timeout 10m "${LST_REPO_PG[@]}"
install mediator-pg     splice-postgres      postgres-mediator.yaml    --wait --timeout 10m "${LST_REPO_PG[@]}"
install participant-pg  splice-postgres      postgres-participant.yaml --wait --timeout 10m "${LST_REPO_PG[@]}"
install apps-pg         splice-postgres      postgres-apps.yaml        --wait --timeout 10m "${LST_REPO_PG[@]}"
install global-domain-0 splice-global-domain global-domain.yaml \
    --set sequencer.driver.password="$STR_PG_PASSWORD" \
    "${LST_REPO[@]}" "${LST_INIT_GD[@]}"
install participant     splice-participant   participant.yaml \
    "${LST_REPO[@]}" "${LST_INIT[@]}"
install scan            splice-scan          scan.yaml \
    --set spliceInstanceNames.networkName="$STR_NETWORK_NAME" \
    "${LST_REPO[@]}" "${LST_INIT[@]}"
install sv              splice-sv-node       sv.yaml \
    --set auth.jwksUrl="$STR_JWKS" \
    --set auth.audience="$STR_OIDC_AUDIENCE" \
    --set spliceInstanceNames.networkName="$STR_NETWORK_NAME" \
    --set pvc.volumeStorageClass="$STR_STORAGE_CLASS" \
    "${LST_REPO[@]}" "${LST_INIT[@]}"
install validator       splice-validator     validator.yaml \
    --set auth.jwksUrl="$STR_JWKS" \
    --set auth.audience="$STR_OIDC_AUDIENCE" \
    --set spliceInstanceNames.networkName="$STR_NETWORK_NAME" \
    --set pvc.volumeStorageClass="$STR_STORAGE_CLASS" \
    "${LST_REPO[@]}" "${LST_INIT[@]}"

echo
echo "=== installed. The DSO is not founded until sv-app answers readyz."
echo "    kubectl -n $STR_NAMESPACE get pods -w"
